"""Local PCM integrity/level report; never infers capture policy or transcribes speech."""
import argparse
import array
import json
import math
import sys
import wave
from pathlib import Path


def analyze(path):
    with wave.open(str(path), "rb") as wav:
        if (wav.getnchannels(), wav.getsampwidth()) != (1, 2):
            raise ValueError("Expected mono PCM16")
        rate = wav.getframerate()
        frames = wav.getnframes()
        windows = []
        peak = zeros = clipping = count = 0
        while data := wav.readframes(rate):
            samples = array.array("h", data)
            if sys.byteorder != "little":
                samples.byteswap()
            squares = sum(x * x for x in samples)
            rms = math.sqrt(squares / len(samples)) / 32768
            block_peak = max(map(abs, samples))
            zeros += sum(x == 0 for x in samples)
            clipping += sum(abs(x) >= 32767 for x in samples)
            peak = max(peak, block_peak)
            windows.append({"start_s": count / rate, "samples": len(samples),
                            "rms_dbfs": round(20 * math.log10(rms), 2) if rms else -120,
                            "peak": block_peak})
            count += len(samples)
        return {"file": str(path), "rate": rate, "channels": 1, "pcm_bits": 16,
                "duration_s": frames / rate, "decoded_samples": count,
                "header_matches_data": frames == count,
                "file_bytes": path.stat().st_size,
                "expected_file_bytes": 44 + frames * 2,
                "peak": peak, "zero_fraction": zeros / max(1, count),
                "clipped_fraction": clipping / max(1, count), "windows": windows}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("wav", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = json.dumps(analyze(args.wav), ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(report + "\n", encoding="utf-8")
    else:
        print(report)
