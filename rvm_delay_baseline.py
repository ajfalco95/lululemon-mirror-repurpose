import cv2
import torch
import numpy as np
import time
import random
from collections import deque

device = torch.device("mps" if torch.backends.mps.is_available() else "cpu")
print("Using:", device)

model = torch.hub.load(
    "PeterL1n/RobustVideoMatting",
    "mobilenetv3",
    pretrained=True
).eval().to(device)

cap = cv2.VideoCapture(0)

if not cap.isOpened():
    raise RuntimeError("Could not open webcam")

width = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
height = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
fps = cap.get(cv2.CAP_PROP_FPS)

if fps <= 0:
    fps = 30.0

fourcc = cv2.VideoWriter_fourcc(*"mp4v")
writer = cv2.VideoWriter(
    "rvm_delay_recording.mp4",
    fourcc,
    fps,
    (width, height)
)

rec = [None] * 4
buffer = deque()

MAX_BUFFER_SECONDS = 5.0

# Random delay behavior
MIN_DELAY = 0.2
MAX_DELAY = 0.8
HOLD_MIN = 0.4
HOLD_MAX = 1.5
DELAY_LERP = 0.08

delay_current = 0.4
delay_target = 0.4
next_delay_change = time.monotonic() + 1.0

with torch.no_grad():
    while True:
        ok, frame = cap.read()

        if not ok:
            break

        frame = cv2.flip(frame, 1)

        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)

        src = torch.from_numpy(rgb).to(device)
        src = src.permute(2, 0, 1).unsqueeze(0).float() / 255.0

        fgr, pha, *rec = model(
            src,
            *rec,
            downsample_ratio=0.25
        )

        fgr = fgr[0].permute(1, 2, 0).cpu().numpy()
        pha = pha[0, 0].cpu().numpy()

        # Mild alpha cleanup
        pha = np.clip((pha - 0.08) / 0.84, 0.0, 1.0)
        pha = pha ** 1.05
        pha = cv2.GaussianBlur(pha, (0, 0), 0.6)

        now = time.monotonic()

        buffer.append(
            (
                now,
                fgr.copy(),
                pha.copy()
            )
        )

        # Pick a new target delay occasionally
        if now >= next_delay_change:
            delay_target = random.uniform(MIN_DELAY, MAX_DELAY)
            next_delay_change = now + random.uniform(HOLD_MIN, HOLD_MAX)

        # Smoothly drift toward it
        delay_current += (delay_target - delay_current) * DELAY_LERP
        delay = delay_current

        target_time = now - delay

        while (
            len(buffer) > 1
            and buffer[1][0] < now - MAX_BUFFER_SECONDS
        ):
            buffer.popleft()

        delayed_fgr = fgr
        delayed_pha = pha

        for timestamp, old_fgr, old_pha in reversed(buffer):
            if timestamp <= target_time:
                delayed_fgr = old_fgr
                delayed_pha = old_pha
                break

        output = delayed_fgr * delayed_pha[..., None]

        output = np.clip(
            output * 255,
            0,
            255
        ).astype(np.uint8)

        output = cv2.cvtColor(
            output,
            cv2.COLOR_RGB2BGR
        )

        cv2.imshow("RVM Delayed Mirror", output)

        if cv2.waitKey(1) & 0xFF == ord("q"):
            break

writer.release()
cap.release()
cv2.destroyAllWindows()
