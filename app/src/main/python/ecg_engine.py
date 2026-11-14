import numpy as np
from PIL import Image
import json
import os

def _dtw_distance(s1, s2):
    n, m = len(s1), len(s2)
    dtw_matrix = np.full((n + 1, m + 1), np.inf)
    dtw_matrix[0, 0] = 0.0

    for i in range(1, n + 1):
        for j in range(1, m + 1):
            cost = abs(s1[i - 1] - s2[j - 1])
            dtw_matrix[i, j] = cost + min(
                dtw_matrix[i - 1, j],
                dtw_matrix[i, j - 1],
                dtw_matrix[i - 1, j - 1]
            )
    return float(dtw_matrix[n, m])

def _bandpass_numpy(signal, fs):
    win_low = max(3, int(0.5 * fs))
    win_high = max(3, int(0.015 * fs))
    baseline = np.convolve(signal, np.ones(win_low) / win_low, mode='same')
    detrended = signal - baseline
    smoothed = np.convolve(detrended, np.ones(win_high) / win_high, mode='same')
    return smoothed

def _find_r_peaks_numpy(filtered, min_dist):
    # W EKG załamki R mają wyraźnie wyższą amplitudę od załamków P i T
    threshold = np.mean(filtered) + 1.8 * np.std(filtered)
    candidates = []
    for i in range(1, len(filtered) - 1):
        if filtered[i] > threshold and filtered[i] >= filtered[i - 1] and filtered[i] > filtered[i + 1]:
            candidates.append(i)

    peaks = []
    for idx in candidates:
        if not peaks or (idx - peaks[-1]) >= min_dist:
            peaks.append(idx)
        elif filtered[idx] > filtered[peaks[-1]]:
            peaks[-1] = idx
    return peaks

def _extract_signal(image_path):
    img = Image.open(image_path).convert('RGB')
    arr = np.array(img, dtype=np.int16)
    h, w, _ = arr.shape

    # Pierwszy pasek wykresu (0 - 10 sek.) w raporcie Huawei Health (układ poziomy):
    # Omijamy górny tekst (0-20%), czarny impuls kalibracyjny po lewej (0-6%) oraz podpisy "0 sek. .. 9 sek." poniżej
    y_start = int(h * 0.20)
    y_end = int(h * 0.41)
    x_start = int(w * 0.06)
    x_end = int(w * 0.985)

    roi = arr[y_start:y_end, x_start:x_end]
    roi_h, roi_w, _ = roi.shape

    # Zapis podglądu wyciętego obszaru (możesz podejrzeć w Device Explorer -> cache/debug_roi.png)
    debug_path = os.path.join(os.path.dirname(image_path), "debug_roi.png")
    Image.fromarray(roi.astype(np.uint8)).save(debug_path)

    # W raporcie Huawei Health linia EKG jest CZERWONA, a siatka milimetrowa JASNOSZARA:
    # Wysoka dominacja kanału R nad kanałami G i B idealnie izoluje wyłącznie linię wykresu
    redness = roi[:, :, 0] - np.maximum(roi[:, :, 1], roi[:, :, 2])
    is_red_trace = (redness > 50) & (roi[:, :, 0] > 130)

    signal = []
    valid_cols = 0
    for x in range(roi_w):
        trace_pixels = np.where(is_red_trace[:, x])[0]
        if len(trace_pixels) > 0:
            y_val = float(roi_h - np.median(trace_pixels))
            signal.append(y_val)
            valid_cols += 1
        else:
            signal.append(np.nan)

    signal = np.array(signal)
    nans = np.isnan(signal)
    if np.all(nans) or valid_cols < 100:
        return [], f"Zbyt mało czerwonych pikseli (valid_cols={valid_cols}/{roi_w}, img={w}x{h})"

    signal[nans] = np.interp(np.nonzero(nans)[0], np.nonzero(~nans)[0], signal[~nans])

    # Pierwszy pasek obejmuje dokładnie ~9.6 sekundy (od x=6% do x=98.5% z 10-sekundowego paska)
    effective_fs = len(signal) / 9.6
    return signal.tolist(), effective_fs, f"OK (valid_cols={valid_cols}/{roi_w}, fs={effective_fs:.1f}Hz)"

def _process_beats(signal, fs):
    signal = np.array(signal)
    if len(signal) < fs:
        return [], 0

    filtered = _bandpass_numpy(signal, fs=fs)
    min_dist = int(0.45 * fs)  # maks. ~133 ud./min
    peaks = _find_r_peaks_numpy(filtered, min_dist)

    pre = int(0.20 * fs)   # 200 ms przed szczytem R (zaimek P + odcinek PQ)
    post = int(0.40 * fs)  # 400 ms po szczycie R (zespół QRS + odcinek ST + zaimek T)
    beats = []
    for p in peaks:
        start = p - pre
        end = p + post
        if start >= 0 and end < len(filtered):
            beat = filtered[start:end]
            # Resampling każdego uderzenia do stałej długości 150 próbek dla spójnego DTW
            x_old = np.linspace(0, 1, len(beat))
            x_new = np.linspace(0, 1, 150)
            beat_resampled = np.interp(x_new, x_old, beat)

            norm = (beat_resampled - np.min(beat_resampled)) / (np.max(beat_resampled) - np.min(beat_resampled) + 1e-8)
            beats.append(norm.tolist())
    return beats, len(peaks)

def process_reference(image_path):
    res = _extract_signal(image_path)
    if len(res) == 2:
        return json.dumps({"status": "error", "message": res[1]})

    raw_signal, fs, info = res
    beats, peaks_count = _process_beats(raw_signal, fs)
    if len(beats) < 2:
        return json.dumps({"status": "error", "message": f"Zbyt mało uderzeń (wykryto={len(beats)}, szczyty R={peaks_count}, {info})"})

    # Wybieramy środkowe uderzenie jako wzorzec (najmniej narażone na artefakty brzegowe filtra)
    template = beats[len(beats) // 2]
    return json.dumps({"status": "success", "template": template, "peaks": peaks_count, "info": info})

def verify_ecg(image_path, template_json_str):
    res = _extract_signal(image_path)
    if len(res) == 2:
        return json.dumps({"status": "error", "match": False, "message": res[1]})

    raw_signal, fs, info = res
    beats, peaks_count = _process_beats(raw_signal, fs)
    if len(beats) < 2:
        return json.dumps({"status": "error", "match": False, "message": f"Zbyt mało uderzeń (wykryto={len(beats)}, szczyty R={peaks_count})"})

    template = np.array(json.loads(template_json_str))
    threshold = 12.0  # próg DTW dla znormalizowanego wektora o długości 150 próbek
    matched_count = 0
    distances = []

    for beat in beats:
        distance = _dtw_distance(template, np.array(beat))
        distances.append(round(distance, 2))
        if distance < threshold:
            matched_count += 1

    ratio = matched_count / len(beats)
    is_valid = ratio >= 0.4
    return json.dumps({
        "status": "success",
        "match": is_valid,
        "matched_ratio": round(ratio, 2),
        "peaks": peaks_count,
        "distances": distances
    })