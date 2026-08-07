#!/usr/bin/env python3
"""Build an OpenAI-shaped vision chat-completion request from a JPEG on disk.

Base64-encodes the image and wraps it with a prompt into the
{"model", "messages": [{"role": "user", "content": [text, image_url]}]}
shape that tquic-vlm-server-interface's forward mode relays verbatim to
Ollama's /v1/chat/completions -- nothing between this file and Ollama
re-parses or transforms it. Feed the result to h3_sender.py to drive it
through the MPQUIC tunnel:

    python build_vlm_request.py photo.jpg -o request.json
    python h3_sender.py <phone-ip> request.json --port 47443 \
        --path infer --content-type application/json
"""

import argparse
import base64
import json

DEFAULT_PROMPT = (
    "Briefly describe what is in this image, in a way that can be read "
    "aloud to a user. Mention any potential allergens you can identify."
)


def build(image_path: str, prompt: str, model: str) -> dict:
    with open(image_path, "rb") as fh:
        img_b64 = base64.b64encode(fh.read()).decode()
    return {
        "model": model,
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {
                        "type": "image_url",
                        "image_url": {"url": f"data:image/jpeg;base64,{img_b64}"},
                    },
                ],
            }
        ],
    }


def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument("image", help="path to a JPEG file")
    ap.add_argument("-o", "--output", default="request.json", help="output JSON path")
    ap.add_argument("--prompt", default=DEFAULT_PROMPT, help="prompt text")
    ap.add_argument("--model", default="qwen3-vl:8b", help="model name (default qwen3-vl:8b)")
    args = ap.parse_args()

    body = build(args.image, args.prompt, args.model)
    with open(args.output, "w") as fh:
        json.dump(body, fh)

    img_chars = len(body["messages"][0]["content"][1]["image_url"]["url"])
    print(f"wrote {args.output} ({img_chars} b64 chars, model={args.model!r})")
    print(f"prompt: {args.prompt!r}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
