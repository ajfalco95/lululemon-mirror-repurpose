import subprocess
import cv2
import numpy as np
import time

URL = "http://192.168.1.247:8787/api/v1/camera/live.h264"

WIDTH = 720
HEIGHT = 1280
FRAME_SIZE = WIDTH * HEIGHT * 3

cmd = [
    "ffmpeg",
    "-loglevel", "error",
    "-fflags", "nobuffer",
    "-flags", "low_delay",
    "-f", "h264",
    "-i", URL,
    "-vf", "transpose=2,hflip",
    "-fps_mode", "passthrough",
    "-pix_fmt", "bgr24",
    "-f", "rawvideo",
    "pipe:1",
]

proc = subprocess.Popen(
    cmd,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    bufsize=0,
)

def read_exact(stream, size):
    data = bytearray()

    while len(data) < size:
        chunk = stream.read(size - len(data))

        if not chunk:
            return None

        data.extend(chunk)

    return data

last = time.monotonic()
frames = 0
fps = 0.0

try:
    while True:
        data = read_exact(proc.stdout, FRAME_SIZE)

        if data is None:
            print("Stream ended")
            print(proc.stderr.read().decode(errors="replace"))
            break

        frame = np.frombuffer(data, dtype=np.uint8)
        frame = frame.reshape((HEIGHT, WIDTH, 3))

        frames += 1
        now = time.monotonic()

        if now - last >= 1.0:
            fps = frames / (now - last)
            print(f"{fps:.1f} fps")
            frames = 0
            last = now

        cv2.putText(
            frame,
            f"{fps:.1f} fps",
            (20, 40),
            cv2.FONT_HERSHEY_SIMPLEX,
            1,
            (255, 255, 255),
            2,
        )

        cv2.imshow("Mirror Low Latency", frame)

        if cv2.waitKey(1) & 0xFF == ord("q"):
            break

finally:
    proc.terminate()
    cv2.destroyAllWindows()
