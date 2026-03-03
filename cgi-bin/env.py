print("Content-type: text/plain\r\n\r\n", end="")
import os
for k, v in os.environ.items():
    print(f"{k}={v}")
