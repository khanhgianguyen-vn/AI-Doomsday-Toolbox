# AI Doomsday Toolbox

**An offline AI toolbox for Android that turns one phone, or a cluster of old phones, into a local AI workstation.**

Run local LLMs, Whisper transcription, image generation, distributed inference, dataset creation, offline knowledge tools, and AI-powered utilities directly on Android. The project is built for people who care about privacy, edge AI, on-device AI, distributed compute, and squeezing useful work out of old phones instead of leaving them in a drawer.

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-26-blue.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-Apache%202.0-yellow.svg)](LICENSE)

<!-- IMAGE: Hero overview. Best choice: a clean collage showing the dashboard, distributed inference, dataset creator, Ollama manager, and Tama pet screens so users instantly understand the app is broader than a single chatbot. -->

## Why It's Different

Most Android AI apps focus on one model on one device. AI Doomsday Toolbox goes further: it combines offline AI on Android with distributed inference, local networking tools, phone-to-phone model sharing, and workflows that can reuse old phones as a low-cost Android cluster or pocket edge-compute setup.

If you are searching for a local LLM on Android, offline AI assistant, mobile HPC experiment, phone cluster, Android distributed compute app, or a way to reuse old phones for edge AI workloads, this project is built in that direction.

## Highlights

- Offline AI on Android with local LLM, Whisper, image generation, upscaling, and media tools
- Distributed inference features for coordinating multiple Android devices on the same network
- Built-in Ollama manager, llama native chat, and remote summary tools
- Dataset creator that turns text and PDFs into cleaned, rated instruction-answer pairs with Alpaca export
- Termux + proot tool environment with install helpers, SSH workflows, in-app webview access, and file management
- AI agent workspace with custom tools, custom agents, and project memory
- Tama virtual pet systems with adventures, farming, chat, and persistent memories

## Features

### Distributed Inference And Android Cluster Workflows

- Run distributed inference features across multiple Android devices
- Experiment with turning old phones into a low-cost phone cluster for local AI workloads
- Monitor worker/master flows from inside the app
- Share models and services over the local network for offline collaboration

<!-- IMAGE: Distributed inference / Android cluster. Best choice: a screenshot that clearly shows multiple nodes, device roles, or a network visualization so users immediately understand the "old phones as compute nodes" story. -->

### Local AI Chat

- Chat with local LLMs on Android
- Connect to llama.cpp-compatible servers and llama-server backends through a native in-app chat UI
- Use Ollama-compatible workflows where they fit best for your setup
- Keep inference on-device or on your own local network instead of relying on cloud chat

<!-- IMAGE: Llama native chat. Best choice: a conversation view with model info, generation stats, and a strong prompt/response example. -->

### Ollama Manager

- Add and manage Ollama servers from inside the app
- Pull, inspect, copy, delete, and organize models
- View and edit Modelfiles
- Create derived models without leaving the Android interface

<!-- IMAGE: Ollama manager / Modelfile editor. Best choice: one screenshot of the model list and one of the Modelfile editor with a create/edit dialog open. -->

### Benchmarking

- Benchmark your device for LLM workloads
- Compare thread counts to find the best number of threads for a specific model
- Save benchmark results for later reference
- Use real llama benchmarking output instead of guessing performance

<!-- IMAGE: Benchmark screen. Best choice: a result view that shows thread counts and tokens-per-second so the feature benefit is obvious at a glance. -->

### Dataset Creator

- Import `.txt` and PDF files
- Split source material into chunks for cleaner processing
- Clean chunks before question generation
- Generate five questions per chunk using neighboring chunk context for better continuity
- Generate answers, rate the pairs, and export the best entries
- Export in Alpaca JSON format
- Customize the prompts used for cleaning, question generation, answer generation, and review

<!-- IMAGE: Dataset creator. Best choice: a project screen showing chunk counts, prompt customization, ratings, and an export action to communicate the full pipeline. -->

### Termux Tools And Proot Workflows

- Connect to a proot distro over SSH from inside the app
- Follow in-app setup help for enabling SSH inside the proot environment
- Install predefined tools such as Ollama, Open WebUI, Big-AGI, Oobabooga text-generation-webui, FastSDCPU, and experimental A1111 workflows
- Open compatible tools in an in-app webview
- Manage remote files with the built-in Termux file manager
- Optionally expose some services outside `localhost` when your workflow needs LAN access

**Note:** A1111 / AUTOMATIC1111 support is still experimental and actively being worked on.

<!-- IMAGE: Termux tools. Best choice: one screenshot of the tool cards/install flow, one of the SSH/proot helper instructions, and one of the in-app webview or file manager. -->

### AI Agent Workspace

- Run an AI coding/workflow agent environment powered by Termux and Ollama-compatible backends
- Create custom tools and custom agents
- Keep project-specific workspace memory and task context
- Build reusable automation flows around your own projects

<!-- IMAGE: AI agent workspace. Best choice: the chat/workspace screen with visible tool activity or custom-agent controls. -->

### PDF, Video, And Summary Tools

- Extract text from PDFs with OCR fallback when needed
- Summarize PDFs, videos, and transcription workflows
- Use Ollama and/or llama.cpp-compatible remote backends for summary generation
- Tune prompts, context limits, output length, and related summary parameters per workflow

<!-- IMAGE: Summary tools. Best choice: a PDF or video summary screen showing markdown output and the remote-backend controls. -->

### Audio, Video, And Subtitle Tools

- Transcribe audio and video with Whisper
- Summarize video content after transcription
- Burn subtitles into video with styling controls such as font, color, and position
- Process media directly from Android share intents

<!-- IMAGE: Subtitle burning. Best choice: a before/after view or settings screen that clearly shows font, color, and subtitle-position controls. -->

### Image Generation And Upscaling

- Generate images with Stable Diffusion workflows directly on Android
- Upscale images and videos with RealESRGAN-based tools
- Use FastSDCPU in Termux/proot workflows for additional image-generation setups
- Experiment with A1111-style web UI workflows through the Termux tools area

<!-- IMAGE: Image generation + upscaling. Best choice: one generation result grid and one upscaling comparison image. -->

### Offline Knowledge, Sharing, And Utilities

- Browse offline knowledge bases with Kiwix and ZIM file support
- Share models and files over LAN
- Create and manage notes
- Use Android share intents to send PDFs, videos, images, and audio into the app’s processing flows

<!-- IMAGE: Kiwix / sharing / notes. Best choice: a compact collage showing the Kiwix library, model sharing QR/network screen, and note viewer. -->

### Tama Virtual Pet

- Raise a Tamagotchi-like pet inside the app
- Go on AI-generated adventures
- Work, farm, and interact across multiple gameplay systems
- Talk to your pet and build memories over time
- Discover different personalities and long-term companionship mechanics

<!-- IMAGE: Tama / pet system. Best choice: a screenshot of the pet home screen plus either the farm or adventure screen to show it is a real subsystem, not a novelty popup. -->

## Built With

### Core AI And Media Projects

- [llama.cpp](https://github.com/ggerganov/llama.cpp)
- [whisper.cpp](https://github.com/ggerganov/whisper.cpp)
- [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp)
- [FFmpeg](https://ffmpeg.org)
- [Kiwix-tools](https://github.com/kiwix/kiwix-tools)
- [Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN)

### Compatible And Integrated Tooling

- [Ollama](https://github.com/ollama/ollama)
- [Open WebUI](https://github.com/open-webui/open-webui)
- [Big-AGI](https://github.com/enricoros/big-AGI)
- [Oobabooga text-generation-webui](https://github.com/oobabooga/text-generation-webui)
- [FastSDCPU](https://github.com/rupeshs/fastsdcpu)
- [AUTOMATIC1111 / stable-diffusion-webui](https://github.com/AUTOMATIC1111/stable-diffusion-webui)
- [EasyDataset](https://github.com/ConardLi/easy-dataset)
- [Termux](https://github.com/termux)

## Getting Started

### Join The Google Play Beta

If you want the easiest install path, you can join the Google Play beta here:

[AI Doomsday Toolbox on Google Play](https://play.google.com/store/apps/details?id=com.manuxd32.aidoomsdaytoolbox)

The Google Play version uses an Android App Bundle, so the installation is usually smaller than downloading a universal package manually. Joining the beta also helps a lot by improving testing coverage, surfacing device-specific issues, and making it easier to validate updates before wider releases.

### Requirements

- Android 8.0+ (API 26)
- `arm64-v8a` device
- More RAM and storage if you plan to run larger local models
- Additional devices on the same network if you want to experiment with distributed inference and phone-cluster workflows

### Build From Source

```bash
git clone https://github.com/ManuXD32/AI-Doomsday-Toolbox.git
cd AI-Doomsday-Toolbox

./gradlew assembleDebug
```

For release bundles, the project expects Java 21 and the usual signing environment variables:

```bash
KEYSTORE_PATH=/absolute/path/to/your-release.keystore \
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
KEYSTORE_PASSWORD=your_keystore_password \
KEY_PASSWORD=your_key_password \
./gradlew :app:bundleRelease
```

## Support

If this project helps you, you can support development here:

- [Ko-fi](https://ko-fi.com/L3L61QAJ1S)
- [PayPal](https://paypal.me/ManuelG815)

## License

This project is licensed under the [Apache License 2.0](LICENSE).

## Author

**ManuXD32** - [GitHub](https://github.com/ManuXD32)
- Built with the help of codex and antigravity
