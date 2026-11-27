
import numpy as np
from PIL import Image
import json


def _dtw_tight(s1, s2, window=3):
    """Ciasne, kontrolowane DTW dla wybranych segmentów cyklu EKG."""
    n, m = len(s1), len(s2)

    if n == 0 or m == 0:
        return float("inf")

    window = max(window, abs(n - m))

    dtw_matrix = np.full((n + 1, m + 1), np.inf)
    dtw_matrix[0, 0] = 0.0

    for i in range(1, n + 1):
        j_start = max(1, i - window)
        j_end = min(m, i + window)

        for j in range(j_start, j_end + 1):
            cost = abs(s1[i - 1] - s2[j - 1])

            dtw_matrix[i, j] = cost + min(
                dtw_matrix[i - 1, j],
                dtw_matrix[i, j - 1],
                dtw_matrix[i - 1, j - 1]
            )

    return float(dtw_matrix[n, m] / n)


def _beat_distance(template, beat):
    """
    Hybrydowa metryka porównująca kształt uderzenia EKG:
    1. Sztywne porównanie QRS.
    2. Ciasne DTW dla fali T.
    3. Porównanie pierwszych pochodnych.
    4. Korelacja Pearsona.
    """

    # 1. QRS - bez rozciągania czasu
    qrs_diff = float(
        np.mean(
            np.abs(template[45:59] - beat[45:59])
        )
    )

    # 2. DTW dla fali T i odcinka ST
    dtw_t_wave = _dtw_tight(
        template[60:135],
        beat[60:135],
        window=3
    )

    # 3. Różnica nachyleń
    slope_diff = float(
        np.mean(
            np.abs(np.diff(template) - np.diff(beat))
        )
    )

    # 4. Korelacja kształtu
    corr = np.corrcoef(template, beat)[0, 1]

    if np.isnan(corr):
        corr = 0.0

    corr_dist = float(1.0 - corr)

    # Łączny wynik
    return (
        0.35 * qrs_diff
        + 0.30 * dtw_t_wave
        + 0.20 * slope_diff
        + 0.15 * corr_dist
    )


def _pan_tompkins_r_peaks(signal, fs):
    """Detekcja szczytów R."""

    win_low = max(3, int(0.18 * fs))
    win_high = max(2, int(0.035 * fs))

    baseline = np.convolve(
        signal,
        np.ones(win_low) / win_low,
        mode="same"
    )

    bandpassed = np.convolve(
        signal - baseline,
        np.ones(win_high) / win_high,
        mode="same"
    )

    # Pochodna
    diff = np.zeros_like(bandpassed)

    for n in range(2, len(bandpassed) - 2):
        diff[n] = (
            -bandpassed[n - 2]
            - 2.0 * bandpassed[n - 1]
            + 2.0 * bandpassed[n + 1]
            + bandpassed[n + 2]
        ) / 8.0

    # Potęgowanie
    squared = diff ** 2

    # Integracja ruchomym oknem
    int_win = max(3, int(0.15 * fs))

    integrated = np.convolve(
        squared,
        np.ones(int_win) / int_win,
        mode="same"
    )

    signal_peak = np.percentile(integrated, 95)
    noise_level = np.percentile(integrated, 50)

    threshold = noise_level + 0.25 * (
        signal_peak - noise_level
    )

    min_dist = int(0.42 * fs)

    rough_peaks = []

    for i in range(1, len(integrated) - 1):
        if (
            integrated[i] > threshold
            and integrated[i] >= integrated[i - 1]
            and integrated[i] > integrated[i + 1]
        ):
            if (
                not rough_peaks
                or (i - rough_peaks[-1]) >= min_dist
            ):
                rough_peaks.append(i)

            elif integrated[i] > integrated[rough_peaks[-1]]:
                rough_peaks[-1] = i

    # Dokładniejsze położenie szczytu R
    search_radius = max(2, int(0.075 * fs))

    r_peaks = []

    for rp in rough_peaks:
        s = max(0, rp - search_radius)
        e = min(
            len(bandpassed),
            rp + search_radius + 1
        )

        if e <= s:
            continue

        exact_r = s + int(
            np.argmax(bandpassed[s:e])
        )

        if (
            not r_peaks
            or (exact_r - r_peaks[-1]) >= min_dist
        ):
            r_peaks.append(exact_r)

    return r_peaks, bandpassed


def _detect_ecg_strips(is_red_mask):
    """
    Znajduje poziome pasy wykresu EKG
    poprzez analizę ciągłości czerwonej linii.
    """

    h, w = is_red_mask.shape

    row_counts = np.sum(is_red_mask, axis=1)

    # Wiersz należy do wykresu, jeśli ma min. 5 czerwonych pikseli
    has_red = row_counts >= 5

    strips = []

    in_strip = False
    start_y = 0
    gap = 0

    max_gap = max(8, int(h * 0.015))

    for y in range(h):
        if has_red[y]:
            if not in_strip:
                in_strip = True
                start_y = y

            gap = 0

        else:
            if in_strip:
                gap += 1

                if gap > max_gap:
                    end_y = y - gap

                    # Odrzucamy małe elementy nagłówka
                    if (end_y - start_y) >= 25:
                        strips.append((
                            max(0, start_y - 10),
                            min(h, end_y + 10)
                        ))

                    in_strip = False
                    gap = 0

    # Obsługa paska kończącego się na dole obrazu
    if in_strip:
        end_y = h - 1

        if (end_y - start_y) >= 25:
            strips.append((
                max(0, start_y - 10),
                min(h, end_y + 10)
            ))

    return strips


def _extract_beats_from_roi(is_red_roi):
    """Wyodrębnia uderzenia EKG z jednego pasa wykresu."""

    roi_h, roi_w = is_red_roi.shape

    signal = []
    valid_cols = 0

    for x in range(roi_w):
        trace_pixels = np.where(is_red_roi[:, x])[0]

        if len(trace_pixels) > 0:
            y_val = float(
                roi_h - np.median(trace_pixels)
            )

            signal.append(y_val)
            valid_cols += 1

        else:
            signal.append(np.nan)

    signal = np.array(signal, dtype=float)

    nans = np.isnan(signal)

    if np.all(nans) or valid_cols < 80:
        return []

    # Interpolacja brakujących próbek
    signal[nans] = np.interp(
        np.where(nans)[0],
        np.where(~nans)[0],
        signal[~nans]
    )

    # Przybliżona częstotliwość próbkowania
    fs = len(signal) / 9.6

    peaks, filtered = _pan_tompkins_r_peaks(
        signal,
        fs=fs
    )

    if len(peaks) < 3:
        return []

    rr_intervals = np.diff(peaks)

    median_rr = float(
        np.median(rr_intervals)
    )

    pre = int(0.28 * median_rr)
    post = int(0.52 * median_rr)

    beats = []

    for p in peaks:
        start = p - pre
        end = p + post

        if start >= 0 and end < len(filtered):
            beat = filtered[start:end]

            if len(beat) < 2:
                continue

            # Resampling do 150 próbek
            x_old = np.linspace(
                0, 1, len(beat)
            )

            x_new = np.linspace(
                0, 1, 150
            )

            beat_resampled = np.interp(
                x_new,
                x_old,
                beat
            )

            std = np.std(beat_resampled)

            if std > 1e-6:
                norm = (
                    beat_resampled
                    - np.mean(beat_resampled)
                ) / std

                beats.append(norm)

    return beats


def _extract_all_beats(image_path):
    """Wyodrębnia wszystkie uderzenia z obrazu PDF."""

    img = Image.open(image_path).convert("RGB")

    arr = np.array(
        img,
        dtype=np.int16
    )

    h, w, _ = arr.shape

    # Przycięcie marginesów
    x_start = int(w * 0.06)
    x_end = int(w * 0.985)

    cropped = arr[:, x_start:x_end]

    # Wykrywanie czerwieni
    redness = (
        cropped[:, :, 0]
        - np.maximum(
            cropped[:, :, 1],
            cropped[:, :, 2]
        )
    )

    is_red_mask = (
        (redness > 30)
        & (cropped[:, :, 0] > 110)
    )

    strips = _detect_ecg_strips(
        is_red_mask
    )

    all_beats = []

    for y0, y1 in strips:
        all_beats.extend(
            _extract_beats_from_roi(
                is_red_mask[y0:y1, :]
            )
        )

    debug_info = (
        f"img={w}x{h}, "
        f"strips={strips}, "
        f"beats={len(all_beats)}"
    )

    return all_beats, debug_info


def process_reference(image_path):
    """Tworzy wzorzec EKG na podstawie pliku PDF."""

    beats, debug_info = _extract_all_beats(
        image_path
    )

    if len(beats) < 6:
        return json.dumps({
            "status": "error",
            "message": (
                f"Zbyt mało uderzeń ({debug_info})"
            )
        })

    # Poprawione: wcześniej brakowało tych zmiennych
    beats_arr = np.array(beats)

    initial_median = np.median(
        beats_arr,
        axis=0
    )

    # Porównanie uderzeń z medianą początkową
    dists = [
        _beat_distance(initial_median, b)
        for b in beats_arr
    ]

    # Odrzucenie najbardziej odstających 30%
    cutoff = np.percentile(
        dists,
        70
    )

    clean_beats = [
        b
        for b, d in zip(beats_arr, dists)
        if d <= cutoff
    ]

    if len(clean_beats) == 0:
        return json.dumps({
            "status": "error",
            "message": (
                f"Nie udało się wybrać uderzeń ({debug_info})"
            )
        })

    # Utworzenie wzorca
    template = np.median(
        clean_beats,
        axis=0
    )

    template = (
        template - np.mean(template)
    ) / (
        np.std(template) + 1e-8
    )

    return json.dumps({
        "status": "success",
        "template": template.tolist(),
        "total_beats": len(beats),
        "clean_beats_used": len(clean_beats),
        "debug": debug_info
    })


def verify_ecg(image_path, template_json_str):
    """Porównuje EKG z zapisanym wzorcem."""

    beats, debug_info = _extract_all_beats(
        image_path
    )

    if len(beats) < 6:
        return json.dumps({
            "status": "error",
            "match": False,
            "message": (
                f"Zbyt mało uderzeń ({debug_info})"
            )
        })

    template = np.array(
        json.loads(template_json_str)
    )

    distances = [
        _beat_distance(template, b)
        for b in beats
    ]

    best_30_dist = float(
        np.percentile(distances, 30)
    )

    median_dist = float(
        np.median(distances)
    )

    # Progi porównania
    threshold = 0.15

    is_valid = (
        best_30_dist < threshold
        and median_dist < 0.25
    )

    return json.dumps({
        "status": "success",
        "match": is_valid,
        "best_30_dist": round(
            best_30_dist, 3
        ),
        "median_dist": round(
            median_dist, 3
        ),
        "total_beats": len(beats),
        "debug": debug_info
    })
