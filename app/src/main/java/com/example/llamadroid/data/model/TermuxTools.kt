package com.example.llamadroid.data.model

import com.example.llamadroid.util.AIConstants

/**
 * Termux tool command catalog for the direct Ubuntu-over-SSH workflow.
 *
 * The current app commands are the source of truth and are meant to run inside
 * an Ubuntu proot session that exposes its own SSH server. Host, port,
 * username, and password stay user-configurable in the UI.
 */

/**
 * Represents a Termux tool that can be installed/run
 */
data class TermuxTool(
    val id: String,
    val name: String,
    val emoji: String,
    val descriptionResId: Int,
    val port: Int,
    val installPath: String,  // Path where tool is installed (for detection)
    val modelsPath: String,   // Path to models folder (if any)
    val requiresMiniconda: Boolean,
    val installCommands: List<String>,
    val installCheckCommand: String,
    val runCommand: String,
    val stopCommand: String,
    val showInstallCard: Boolean = true,
    val isInstalled: Boolean = false,
    val isRunning: Boolean = false,
    val hasWebUI: Boolean = true  // Whether tool has a web UI (false for ollama, mcp)
)

/**
 * Info card content for each tool (from GitHub READMEs)
 */
data class ToolInfo(
    val ramRequirement: String,
    val lowRamTips: List<String>,
    val integration: String,
    val integrationResId: Int? = null,
    val features: List<String>,
    val githubUrl: String
)

/**
 * Tool info cards with accurate specs from GitHub READMEs
 */
object ToolInfoCards {
    val ollama = ToolInfo(
        ramRequirement = "8GB (7B), 16GB (13B), 32GB (33B)",
        lowRamTips = listOf(
            "Use Q2_K or Q3_K quantized models",
            "Stick to 1-3B param models (Phi-2, Qwen2-0.5B, TinyLlama)",
            "Set OLLAMA_NUM_PARALLEL=1 to limit concurrent requests"
        ),
        integration = "Base server for Open WebUI, Big-AGI, and other frontends. Exposes OpenAI-compatible API on port ${AIConstants.Ports.OLLAMA}.",
        features = listOf("Local LLM inference", "Model library", "OpenAI-compatible API", "GGUF support"),
        githubUrl = "https://github.com/ollama/ollama"
    )

    val openWebUI = ToolInfo(
        ramRequirement = "~1GB + model RAM (uses Ollama)",
        lowRamTips = listOf(
            "Disable RAG features if not needed",
            "Disable web search to save memory",
            "Use lightweight theme"
        ),
        integration = "Connect to Ollama at localhost:${AIConstants.Ports.OLLAMA}. For FastSD images: add FastSD MCP URL in Settings → Function Calling → Native.",
        features = listOf("RAG support", "Web search", "Voice/video chat", "Model builder", "Python function calling", "Image generation via tools"),
        githubUrl = "https://github.com/open-webui/open-webui"
    )

    val bigAGI = ToolInfo(
        ramRequirement = "~200-500MB (pure frontend)",
        lowRamTips = listOf(
            "Already lightweight - just a Next.js web app",
            "No local model loading",
            "All inference handled by backend (Ollama)"
        ),
        integration = "Configure Ollama endpoint in Settings → Models → Ollama. Use 'Beam' for multi-model comparison.",
        features = listOf("AI personas", "Beam multi-model chat", "Code execution", "PDF import", "Response streaming"),
        githubUrl = "https://github.com/ManuXD32/big-AGI/tree/v2-dev"
    )

    val oobabooga = ToolInfo(
        ramRequirement = "4GB+ (llama.cpp backend)",
        lowRamTips = listOf(
            "Use llama.cpp backend (default in portable builds)",
            "Load Q2_K or Q3_K GGUF models",
            "Enable --cpu flag for CPU-only inference",
            "Portable builds need no installation"
        ),
        integration = "100% offline, zero telemetry. OpenAI-compatible API for tool calling. Extensions available.",
        features = listOf("Multiple backends (llama.cpp, Transformers, ExLlamaV3)", "Image generation", "Vision models", "Web search", "Extensions"),
        githubUrl = "https://github.com/ManuXD32/textgen"
    )

    val fastsdcpu = ToolInfo(
        ramRequirement = "2-5GB (SD Turbo + TAESD)",
        lowRamTips = listOf(
            "Use SD Turbo or SDXS-512 models (1 step, fastest)",
            "Enable TAESD (Tiny Decoder) - saves ~2GB RAM",
            "Use 256x256 or 512x512 resolution",
            "Set guidance_scale=1 (higher uses more RAM)"
        ),
        integration = "Web UI: http://127.0.0.1:7860. MCP Server: python src/app.py --mcp (port 8000). OpenWebUI: add http://127.0.0.1:8000 in Settings, set Function Calling to Native.",
        features = listOf("Desktop GUI, Web UI, CLI", "OpenVINO support", "LCM-LoRA", "ControlNet", "Real-time generation", "Raspberry Pi + Android support"),
        githubUrl = "https://github.com/ManuXD32/fastsdcpu"
    )

    val a1111 = ToolInfo(
        ramRequirement = "8GB+ minimum",
        lowRamTips = listOf(
            "⚠️ NOT recommended for low RAM",
            "Use FastSD CPU instead - faster and works on Android",
            "If needed: use --lowvram or --medvram flags"
        ),
        integration = "",
        integrationResId = com.example.llamadroid.R.string.tool_a1111_integration,
        features = listOf("Stable Diffusion WebUI", "Extensions", "ControlNet", "LoRA support"),
        githubUrl = "https://github.com/ManuXD32/stable-diffusion-webui"
    )

    // Note: AI Agent has its own setup at AIHubScreen -> AgentScreen

    fun getInfo(toolId: String): ToolInfo? = when (toolId) {
        "ollama" -> ollama
        "open_webui" -> openWebUI
        "big_agi" -> bigAGI
        "oobabooga" -> oobabooga
        "fastsdcpu" -> fastsdcpu
        "a1111" -> a1111
        else -> null
    }
}

/**
 * All available Termux tools - commands run inside Ubuntu via SSH
 */
object TermuxTools {

    // Default/example SSH port. Users can override this in the connection form.
    const val DEFAULT_SSH_PORT = 8025

    // Ports for the services
    object Ports {
        const val OLLAMA = AIConstants.Ports.OLLAMA
        const val OPEN_WEBUI = 8082
        const val BIG_AGI = 8081
        const val OOBABOOGA = 7861
        const val FASTSDCPU = 7860
        const val FASTSDCPU_MCP = 8000
        const val A1111 = 7865
    }

    // Model folder paths
    object ModelPaths {
        const val OLLAMA = "~/.ollama/models"
        const val OOBABOOGA = "~/text-generation-webui/models"
        const val FASTSDCPU = "/home/fastsd/fastsdcpu/models"
        const val A1111 = "/home/auto/stable-diffusion-webui/models"
        const val NONE = ""
    }

    private const val BIG_AGI_REPO = "https://github.com/ManuXD32/big-AGI.git"
    private const val BIG_AGI_BRANCH = "v2-dev"
    private const val OOBABOOGA_REPO = "https://github.com/ManuXD32/textgen"
    private const val FASTSDCPU_REPO = "https://github.com/ManuXD32/fastsdcpu"
    private const val A1111_REPO = "https://github.com/ManuXD32/stable-diffusion-webui.git"
    private const val OLLAMA_PROCESS_PATTERN = "ollama serve"
    private const val OPEN_WEBUI_PROCESS_PATTERN = "open-webui serve --port 8082"
    private const val BIG_AGI_PROCESS_PATTERN = "next start --port 8081"
    private const val BIG_AGI_BUILD_PROCESS_PATTERN = "node /root/big-AGI/node_modules/.bin/next build"
    private const val BIG_AGI_NPM_BUILD_PATTERN = "npm run build"
    private const val OOBABOOGA_PROCESS_PATTERN = "server.py --cpu --listen-port 7861"
    private const val FASTSDCPU_WEBUI_SCRIPT_PATTERN = "bash start-webui.sh"
    private const val FASTSDCPU_MCP_SCRIPT_PATTERN = "bash start-mcpserver.sh"
    private const val FASTSDCPU_WEBUI_PROCESS_PATTERN = "src/app.py -w"
    private const val FASTSDCPU_MCP_PROCESS_PATTERN = "src/app.py --mcp"
    private const val FASTSDCPU_WEBUI_PID_FILE = "/tmp/fastsdcpu-webui.pid"
    private const val FASTSDCPU_MCP_PID_FILE = "/tmp/fastsdcpu-mcp.pid"
    private const val FASTSDCPU_WEBUI_LOG_FILE = "/tmp/fastsdcpu-webui.log"
    private const val FASTSDCPU_MCP_LOG_FILE = "/tmp/fastsdcpu-mcp.log"
    private const val OLLAMA_PID_FILE = "/tmp/ollama.pid"
    private const val OLLAMA_LOG_FILE = "/tmp/ollama.log"
    private const val OPEN_WEBUI_PID_FILE = "/tmp/open-webui.pid"
    private const val OPEN_WEBUI_LOG_FILE = "/tmp/open-webui.log"
    private const val BIG_AGI_PID_FILE = "/tmp/big-agi.pid"
    private const val BIG_AGI_LOG_FILE = "/tmp/big-agi.log"
    private const val OOBABOOGA_PID_FILE = "/tmp/oobabooga.pid"
    private const val OOBABOOGA_LOG_FILE = "/tmp/oobabooga.log"
    private const val A1111_STABLE_DIFFUSION_REPO_MIRROR = "https://github.com/joypaul162/Stability-AI-stablediffusion.git"
    private const val A1111_STABLE_DIFFUSION_COMMIT = "cf1d67a6fd5ea1aa600c4df58e5b47da45f6bdbf"
    private const val A1111_CLIP_PACKAGE = "https://github.com/openai/CLIP/archive/d50d76daa670286dd6cacf3bcd80b5e4823fc8e1.zip"
    private const val A1111_ASSETS_REPO = "https://github.com/AUTOMATIC1111/stable-diffusion-webui-assets.git"
    private const val A1111_ASSETS_COMMIT = "6f7db241d2f8ba7457bac5ca9753331f0c266917"
    private const val A1111_SDXL_REPO = "https://github.com/Stability-AI/generative-models.git"
    private const val A1111_SDXL_COMMIT = "45c443b316737a4ab6e40413d7794a7f5657c19f"
    private const val A1111_K_DIFFUSION_COMMIT = "ab527a9a6d347f364e3d185ba6d714e22d80cb3c"
    private const val A1111_BLIP_COMMIT = "48211a1594f1321b00f14c9f7a5b4813144b2fb9"
    private const val A1111_LAUNCH_PATTERN = "stable-diffusion-webui/launch.py"

    // ============ MINICONDA INSTALLATION (PREREQUISITE) ============
    val MINICONDA_INSTALL_COMMANDS = listOf(
        "apt update && apt upgrade -y",
        "apt install curl -y",
        "curl -O https://repo.anaconda.com/miniconda/Miniconda3-latest-Linux-aarch64.sh",
        "bash Miniconda3-latest-Linux-aarch64.sh -b -p ~/miniconda3",
        "~/miniconda3/bin/conda tos accept --override-channels --channel https://repo.anaconda.com/pkgs/main",
        "~/miniconda3/bin/conda tos accept --override-channels --channel https://repo.anaconda.com/pkgs/r"
    )

    // ============ TOOLS ============

    val ollama = TermuxTool(
        id = "ollama",
        name = "Ollama",
        emoji = "🦙",
        descriptionResId = com.example.llamadroid.R.string.tool_ollama_desc,
        port = Ports.OLLAMA,
        installPath = "~/.ollama",  // Ollama data directory (contains models)
        modelsPath = ModelPaths.OLLAMA,
        requiresMiniconda = false,
        installCommands = listOf(
            "apt update && apt upgrade -y",
            "apt install ca-certificates curl zstd -y",
            "update-ca-certificates -v",
            "DEBIAN_FRONTEND=noninteractive dpkg-reconfigure ca-certificates",
            "curl -fsSL https://ollama.com/install.sh | sh"
        ),
        installCheckCommand = buildInstallCheckCommand("command -v ollama >/dev/null 2>&1"),
        runCommand = buildGuardedBackgroundRunCommand(
            pidFile = OLLAMA_PID_FILE,
            logFile = OLLAMA_LOG_FILE,
            launchCommand = "ollama serve",
            runningCondition = buildRunningCondition(OLLAMA_PID_FILE, Ports.OLLAMA, OLLAMA_PROCESS_PATTERN)
        ),
        stopCommand = buildStopCommand(
            pidFile = OLLAMA_PID_FILE,
            processPattern = "ollama serve",
            port = Ports.OLLAMA
        ),
        hasWebUI = false  // Ollama is API-only, no web UI
    )

    val openWebUI = TermuxTool(
        id = "open_webui",
        name = "Open WebUI",
        emoji = "🌐",
        descriptionResId = com.example.llamadroid.R.string.tool_open_webui_desc,
        port = Ports.OPEN_WEBUI,
        installPath = "~/miniconda3/envs/webui",
        modelsPath = ModelPaths.OLLAMA,
        requiresMiniconda = true,
        installCommands = listOf(
            "~/miniconda3/bin/conda create -n webui python=3.11 -y",
            "~/miniconda3/envs/webui/bin/pip install open-webui",
            "apt install libsndfile1 libsndfile1-dev -y"
        ),
        installCheckCommand = buildInstallCheckCommand(
            "test -x ~/miniconda3/envs/webui/bin/python3 && test -x ~/miniconda3/envs/webui/bin/open-webui"
        ),
        runCommand = buildGuardedBackgroundRunCommand(
            pidFile = OPEN_WEBUI_PID_FILE,
            logFile = OPEN_WEBUI_LOG_FILE,
            launchCommand = "~/miniconda3/envs/webui/bin/python3 ~/miniconda3/envs/webui/bin/open-webui serve --port 8082",
            runningCondition = buildRunningCondition(OPEN_WEBUI_PID_FILE, Ports.OPEN_WEBUI, OPEN_WEBUI_PROCESS_PATTERN)
        ),
        stopCommand = buildStopCommand(
            pidFile = OPEN_WEBUI_PID_FILE,
            processPattern = OPEN_WEBUI_PROCESS_PATTERN,
            port = Ports.OPEN_WEBUI
        )
    )

    val bigAGI = TermuxTool(
        id = "big_agi",
        name = "Big-AGI",
        emoji = "🧠",
        descriptionResId = com.example.llamadroid.R.string.tool_big_agi_desc,
        port = Ports.BIG_AGI,
        installPath = "~/big-AGI",
        modelsPath = ModelPaths.NONE,
        requiresMiniconda = false,
        installCommands = listOf(
            "apt update && apt install -y ca-certificates curl gnupg git",
            "mkdir -p /etc/apt/keyrings",
            "curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg",
            "echo 'deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main' | tee /etc/apt/sources.list.d/nodesource.list",
            "apt update && apt install nodejs -y",
            "git clone --branch $BIG_AGI_BRANCH --single-branch $BIG_AGI_REPO",
            "cd big-AGI && npm install -g npm@11.0.0 && npm install && npm run build"
        ),
        installCheckCommand = buildInstallCheckCommand(
            "test -f ~/big-AGI/package.json && test -d ~/big-AGI/node_modules && test -f ~/big-AGI/.next/BUILD_ID"
        ),
        runCommand = buildGuardedBackgroundRunCommand(
            directory = "big-AGI",
            pidFile = BIG_AGI_PID_FILE,
            logFile = BIG_AGI_LOG_FILE,
            launchCommand = buildBigAgiLaunchCommand(networkVisible = false),
            runningCondition = buildRunningCondition(BIG_AGI_PID_FILE, Ports.BIG_AGI, BIG_AGI_PROCESS_PATTERN)
        ),
        stopCommand = buildBigAgiStopCommand()
    )

    val oobabooga = TermuxTool(
        id = "oobabooga",
        name = "Oobabooga",
        emoji = "📝",
        descriptionResId = com.example.llamadroid.R.string.tool_oobabooga_desc,
        port = Ports.OOBABOOGA,
        installPath = "~/text-generation-webui",
        modelsPath = ModelPaths.OOBABOOGA,
        requiresMiniconda = true,
        installCommands = listOf(
            "~/miniconda3/bin/conda create -n textgen python=3.11 -y",
            "~/miniconda3/envs/textgen/bin/pip3 install torch==2.4.1 torchvision==0.19.1 torchaudio==2.4.1 --index-url https://download.pytorch.org/whl/cpu",
            "apt install git -y",
            "git clone $OOBABOOGA_REPO text-generation-webui",
            "cd text-generation-webui && ~/miniconda3/envs/textgen/bin/pip3 install -r requirements/full/requirements_cpu_only.txt"
        ),
        installCheckCommand = buildInstallCheckCommand(
            "test -d ~/text-generation-webui && test -f ~/text-generation-webui/server.py && test -x ~/miniconda3/envs/textgen/bin/python3"
        ),
        runCommand = buildGuardedBackgroundRunCommand(
            directory = "text-generation-webui",
            pidFile = OOBABOOGA_PID_FILE,
            logFile = OOBABOOGA_LOG_FILE,
            launchCommand = "~/miniconda3/envs/textgen/bin/python3 server.py --cpu --listen-port 7861",
            runningCondition = buildRunningCondition(OOBABOOGA_PID_FILE, Ports.OOBABOOGA, OOBABOOGA_PROCESS_PATTERN)
        ),
        stopCommand = buildStopCommand(
            pidFile = OOBABOOGA_PID_FILE,
            processPattern = OOBABOOGA_PROCESS_PATTERN,
            port = Ports.OOBABOOGA
        )
    )

    // FastSD CPU - uses the upstream uv installer with a conda-provided Python 3.11 interpreter
    val fastsdcpu = TermuxTool(
        id = "fastsdcpu",
        name = "FastSD CPU",
        emoji = "🎨",
        descriptionResId = com.example.llamadroid.R.string.tool_fastsdcpu_desc,
        port = Ports.FASTSDCPU,
        installPath = "~/fastsdcpu",
        modelsPath = ModelPaths.FASTSDCPU,
        requiresMiniconda = true,
        installCommands = buildFastSdInstallCommands(),
        installCheckCommand = buildInstallCheckCommand(
            "test -d ~/fastsdcpu && test -x ~/fastsdcpu/start-webui.sh && test -x ~/fastsdcpu/start-mcpserver.sh && test -x ~/miniconda3/envs/fastsdcpu/bin/python3"
        ),
        runCommand = buildFastSdLaunchCommand(
            pidFile = FASTSDCPU_WEBUI_PID_FILE,
            logFile = FASTSDCPU_WEBUI_LOG_FILE,
            scriptName = "start-webui.sh",
            port = Ports.FASTSDCPU,
            networkVisible = false
        ),
        stopCommand = buildFastSdStopCommand(
            pidFile = FASTSDCPU_WEBUI_PID_FILE,
            scriptPattern = FASTSDCPU_WEBUI_SCRIPT_PATTERN,
            appPattern = FASTSDCPU_WEBUI_PROCESS_PATTERN,
            port = Ports.FASTSDCPU
        )
    )

    // FastSD MCP Server - uses same install as FastSD CPU but runs MCP mode
    val fastsdcpuMcp = TermuxTool(
        id = "fastsdcpu_mcp",
        name = "FastSD MCP Server",
        emoji = "🤖",
        descriptionResId = com.example.llamadroid.R.string.tool_mcp_desc,
        port = Ports.FASTSDCPU_MCP,
        installPath = "~/fastsdcpu",  // Same as main FastSD
        modelsPath = ModelPaths.FASTSDCPU,
        requiresMiniconda = true,
        installCommands = buildFastSdInstallCommands(),
        installCheckCommand = buildInstallCheckCommand(
            "test -d ~/fastsdcpu && test -x ~/fastsdcpu/start-webui.sh && test -x ~/fastsdcpu/start-mcpserver.sh && test -x ~/miniconda3/envs/fastsdcpu/bin/python3"
        ),
        runCommand = buildFastSdLaunchCommand(
            pidFile = FASTSDCPU_MCP_PID_FILE,
            logFile = FASTSDCPU_MCP_LOG_FILE,
            scriptName = "start-mcpserver.sh",
            port = Ports.FASTSDCPU_MCP,
            networkVisible = false
        ),
        stopCommand = buildFastSdStopCommand(
            pidFile = FASTSDCPU_MCP_PID_FILE,
            scriptPattern = FASTSDCPU_MCP_SCRIPT_PATTERN,
            appPattern = FASTSDCPU_MCP_PROCESS_PATTERN,
            port = Ports.FASTSDCPU_MCP
        ),
        showInstallCard = false,
        hasWebUI = false  // MCP is API-only, no web UI
    )

    // Automatic 1111 - uses conda Python 3.11 (auto user) plus a mirror for the retired Stable Diffusion repo
    val automatic1111 = TermuxTool(
        id = "a1111",
        name = "Automatic 1111",
        emoji = "🖼️",
        descriptionResId = com.example.llamadroid.R.string.tool_a1111_desc,
        port = Ports.A1111,
        installPath = "/home/auto/stable-diffusion-webui",  // Installed under auto user
        modelsPath = ModelPaths.A1111,
        requiresMiniconda = true,
        installCommands = listOf(
            // Create user 'auto' (webui.sh refuses to run as root)
            "useradd -m -p '' auto --shell /bin/bash || true",
            // Install dependencies
            "apt update && apt install -y wget git libgl1 libglib2.0-0 gcc",
            // Create conda environment with Python 3.11 for auto user
            "~/miniconda3/bin/conda create -n a1111 python=3.11 -y",
            // Clone repo as user auto
            "su - auto -c 'git clone $A1111_REPO'",
            // Pre-clone required repositories to avoid authentication errors during first run
            // These repos are cloned by launch.py but may fail if network has issues or retired upstream URLs
            "su - auto -c 'mkdir -p ~/stable-diffusion-webui/repositories'",
            """su - auto -c 'git clone $A1111_ASSETS_REPO ~/stable-diffusion-webui/repositories/stable-diffusion-webui-assets && git -C ~/stable-diffusion-webui/repositories/stable-diffusion-webui-assets checkout $A1111_ASSETS_COMMIT' || true""",
            """su - auto -c 'git clone $A1111_STABLE_DIFFUSION_REPO_MIRROR ~/stable-diffusion-webui/repositories/stable-diffusion-stability-ai && git -C ~/stable-diffusion-webui/repositories/stable-diffusion-stability-ai checkout $A1111_STABLE_DIFFUSION_COMMIT' || true""",
            """su - auto -c 'git clone $A1111_SDXL_REPO ~/stable-diffusion-webui/repositories/generative-models && git -C ~/stable-diffusion-webui/repositories/generative-models checkout $A1111_SDXL_COMMIT' || true""",
            "su - auto -c 'git clone https://github.com/CompVis/taming-transformers.git ~/stable-diffusion-webui/repositories/taming-transformers' || true",
            """su - auto -c 'git clone https://github.com/crowsonkb/k-diffusion.git ~/stable-diffusion-webui/repositories/k-diffusion && git -C ~/stable-diffusion-webui/repositories/k-diffusion checkout $A1111_K_DIFFUSION_COMMIT' || true""",
            "su - auto -c 'git clone https://github.com/sczhou/CodeFormer.git ~/stable-diffusion-webui/repositories/CodeFormer' || true",
            """su - auto -c 'git clone https://github.com/salesforce/BLIP.git ~/stable-diffusion-webui/repositories/BLIP && git -C ~/stable-diffusion-webui/repositories/BLIP checkout $A1111_BLIP_COMMIT' || true""",
            // Delete existing venv (will pre-create with conda Python)
            "su - auto -c 'rm -rf ~/stable-diffusion-webui/venv'",
            // Pre-create venv with conda Python 3.11
            "su - auto -c '/root/miniconda3/envs/a1111/bin/python -m venv ~/stable-diffusion-webui/venv'",
            // Old CLIP builds still expect pkg_resources, so pre-install it without build isolation.
            """su - auto -c '~/stable-diffusion-webui/venv/bin/pip install "setuptools<81" wheel'""",
            "su - auto -c '~/stable-diffusion-webui/venv/bin/pip install --no-build-isolation $A1111_CLIP_PACKAGE'",
            "su - auto -c '~/stable-diffusion-webui/venv/bin/pip install -r ~/stable-diffusion-webui/requirements_versions.txt'",
            buildA1111ClipEnsureCommand(),
            // Configure webui-user.sh with conda Python
            "su - auto -c 'sed -i \"/#python_cmd/d\" ~/stable-diffusion-webui/webui-user.sh'",
            "su - auto -c 'sed -i \"/#export COMMANDLINE_ARGS/d\" ~/stable-diffusion-webui/webui-user.sh'",
            "su - auto -c 'sed -i \"/STABLE_DIFFUSION_REPO/d\" ~/stable-diffusion-webui/webui-user.sh'",
            "su - auto -c 'echo \"python_cmd=\\\"/root/miniconda3/envs/a1111/bin/python\\\"\" >> ~/stable-diffusion-webui/webui-user.sh'",
            """su - auto -c 'echo "export STABLE_DIFFUSION_REPO=\\"$A1111_STABLE_DIFFUSION_REPO_MIRROR\\"" >> ~/stable-diffusion-webui/webui-user.sh'""",
            "su - auto -c 'echo \"export COMMANDLINE_ARGS=\\\"--port 7865 --api --use-cpu all --precision full --no-half --skip-torch-cuda-test --skip-load-model-at-start --no-download-sd-model --skip-prepare-environment\\\"\" >> ~/stable-diffusion-webui/webui-user.sh'"
        ),
        installCheckCommand = buildInstallCheckCommand(
            "id auto >/dev/null 2>&1 && su - auto -c 'test -f ~/stable-diffusion-webui/webui.sh && test -x ~/stable-diffusion-webui/venv/bin/python && test -f ~/stable-diffusion-webui/webui-user.sh'"
        ),
        runCommand = buildA1111RunCommand(networkVisible = false),
        stopCommand = buildA1111StopCommand()
    )

    // All tools list (AI Agent is separate - see AIHubScreen)
    val allTools = listOf(ollama, openWebUI, bigAGI, oobabooga, fastsdcpu, fastsdcpuMcp, automatic1111)

    fun getTool(id: String): TermuxTool? = allTools.find { it.id == id }

    val installableTools = allTools.filter { it.showInstallCard }

    val installAllCommands = buildList {
        addAll(MINICONDA_INSTALL_COMMANDS)
        installableTools.forEach { tool ->
            addAll(tool.installCommands)
        }
    }

    // Tools that require Miniconda
    val toolsRequiringMiniconda = allTools.filter { it.requiresMiniconda }

    // Tools with model management
    val toolsWithModels = allTools.filter { it.modelsPath.isNotEmpty() }

    // Base setup command
    const val BASE_SETUP = "apt update && apt upgrade -y && apt install curl wget git -y"

    private fun buildInstallCheckCommand(condition: String): String {
        return "if $condition; then echo 'installed'; else echo 'not'; fi"
    }

    private fun buildBackgroundRunCommand(
        directory: String? = null,
        pidFile: String,
        logFile: String,
        launchCommand: String
    ): String {
        val cdPrefix = if (directory.isNullOrBlank()) "" else "cd $directory && "
        return "rm -f $pidFile $logFile && setsid -f bash -lc '$cdPrefix pgid=${'$'}(ps -o pgid= ${'$'}${'$'} | tr -d \" \") && echo ${'$'}pgid > $pidFile && exec $launchCommand > $logFile 2>&1 < /dev/null'"
    }

    private fun buildGuardedBackgroundRunCommand(
        directory: String? = null,
        pidFile: String,
        logFile: String,
        launchCommand: String,
        runningCondition: String
    ): String {
        return """
            if ${runningCondition}; then
              echo 'already running'
            else
              ${buildBackgroundRunCommand(directory, pidFile, logFile, launchCommand)}
            fi
        """.trimIndent()
    }

    private fun buildStopCommand(
        pidFile: String,
        processPattern: String,
        port: Int? = null
    ): String {
        val safeProcessPattern = toSafePgrepPattern(processPattern)
        return """
            ${buildProcessGroupStopBlock(pidFile)}
            pkill -9 -f '$safeProcessPattern' 2>/dev/null || true
            ${port?.let { buildPortOwnerKillBlock(it) } ?: "true"}
        """.trimIndent()
    }

    private fun buildFastSdInstallCommands(): List<String> {
        return listOf(
            "~/miniconda3/bin/conda create -n fastsdcpu python=3.11 -y",
            "apt update && apt install ffmpeg git curl -y && git clone $FASTSDCPU_REPO",
            "curl -LsSf https://astral.sh/uv/install.sh | sh",
            """sed -i 's|uv venv --python 3.11.6 "${'$'}BASEDIR/env"|uv venv --python /root/miniconda3/envs/fastsdcpu/bin/python3 "${'$'}BASEDIR/env"|g' /root/fastsdcpu/install.sh""",
            """sed -i 's|torch==2.8.0 torchvision==0.23.0|torch==2.4.1 torchvision==0.19.1|g' /root/fastsdcpu/install.sh""",
            "sed -i '/read -n1 -r -p /d' /root/fastsdcpu/install.sh",
            """cd fastsdcpu && chmod +x install.sh && PATH="${'$'}HOME/.local/bin:${'$'}PATH" UV_LINK_MODE=copy ./install.sh --disable-gui""",
            "rm -f /root/fastsdcpu/start-mcpserver.sh && cp /root/fastsdcpu/start-webui.sh /root/fastsdcpu/start-mcpserver.sh",
            """sed -i 's|${'$'}PYTHON_COMMAND src/app.py -w|${'$'}PYTHON_COMMAND src/app.py --mcp|g' /root/fastsdcpu/start-mcpserver.sh""",
            "chmod +x /root/fastsdcpu/start-mcpserver.sh"
        )
    }

    private fun buildFastSdLaunchCommand(
        pidFile: String,
        logFile: String,
        scriptName: String,
        port: Int,
        networkVisible: Boolean
    ): String {
        val processPattern = if (scriptName == "start-mcpserver.sh") {
            FASTSDCPU_MCP_PROCESS_PATTERN
        } else {
            FASTSDCPU_WEBUI_PROCESS_PATTERN
        }
        return buildGuardedBackgroundRunCommand(
            directory = "fastsdcpu",
            pidFile = pidFile,
            logFile = logFile,
            launchCommand = "env GRADIO_SERVER_NAME=${getBindHost(networkVisible)} GRADIO_SERVER_PORT=$port bash $scriptName",
            runningCondition = buildRunningCondition(pidFile, port, processPattern)
        )
    }

    private fun buildBigAgiStopCommand(): String {
        val safeStartPattern = toSafePgrepPattern(BIG_AGI_PROCESS_PATTERN)
        val safeBuildPattern = toSafePgrepPattern(BIG_AGI_BUILD_PROCESS_PATTERN)
        val safeNpmBuildPattern = toSafePgrepPattern(BIG_AGI_NPM_BUILD_PATTERN)
        return """
            ${buildProcessGroupStopBlock(BIG_AGI_PID_FILE)}
            pkill -9 -f '$safeStartPattern' 2>/dev/null || true
            pkill -9 -f '$safeBuildPattern' 2>/dev/null || true
            pkill -9 -f '$safeNpmBuildPattern' 2>/dev/null || true
            ${buildPortOwnerKillBlock(Ports.BIG_AGI)}
        """.trimIndent()
    }

    private fun buildFastSdStopCommand(
        pidFile: String,
        scriptPattern: String,
        appPattern: String,
        port: Int
    ): String {
        val safeScriptPattern = toSafePgrepPattern(scriptPattern)
        val safeAppPattern = toSafePgrepPattern(appPattern)
        return """
            ${buildProcessGroupStopBlock(pidFile)}
            pkill -9 -f '$safeScriptPattern' 2>/dev/null || true
            pkill -9 -f '$safeAppPattern' 2>/dev/null || true
            ${buildPortOwnerKillBlock(port)}
        """.trimIndent()
    }

    private fun buildHttpStatusCommand(port: Int): String {
        return "${buildHttpProbeCommand(port)} >/dev/null 2>&1 && echo 'running' || echo 'stopped'"
    }

    private fun buildPidAwareStatusCommand(pidFile: String, port: Int): String {
        return """
            if [ -s $pidFile ]; then
              pgid=${'$'}(cat $pidFile)
              if [ -n "${'$'}pgid" ] && kill -0 -- -${'$'}pgid 2>/dev/null; then
                echo 'running'
              elif ${buildHttpProbeCommand(port)} >/dev/null 2>&1; then
                echo 'running'
              else
                rm -f $pidFile 2>/dev/null || true
                echo 'stopped'
              fi
            elif ${buildHttpProbeCommand(port)} >/dev/null 2>&1; then
              echo 'running'
            else
              echo 'stopped'
            fi
        """.trimIndent()
    }

    private fun buildFastSdStatusCommand(pidFile: String, port: Int, processPattern: String): String {
        return buildPidOrProcessStatusCommand(pidFile, port, processPattern)
    }

    private fun buildPidGroupCondition(pidFile: String): String {
        return """{ [ -s $pidFile ] && pgid=${'$'}(cat $pidFile) && [ -n "${'$'}pgid" ] && kill -0 -- -${'$'}pgid 2>/dev/null; }"""
    }

    private fun buildRunningCondition(pidFile: String, port: Int, processPattern: String? = null): String {
        val pidCondition = buildPidGroupCondition(pidFile)
        val processCondition = processPattern?.let {
            "pgrep -f '${toSafePgrepPattern(it)}' >/dev/null 2>&1"
        }
        val checks = listOfNotNull(
            pidCondition,
            processCondition,
            "${buildHttpProbeCommand(port)} >/dev/null 2>&1"
        )
        return checks.joinToString(" || ")
    }

    private fun buildA1111ClipEnsureCommand(): String {
        return """
            su - auto -c 'if ! ~/stable-diffusion-webui/venv/bin/python -c "import importlib.util, sys; sys.exit(0 if importlib.util.find_spec(\"clip\") else 1)" >/dev/null 2>&1; then ~/stable-diffusion-webui/venv/bin/pip install --no-build-isolation --force-reinstall $A1111_CLIP_PACKAGE; fi; ~/stable-diffusion-webui/venv/bin/python -c "import importlib.util, sys; sys.exit(0 if importlib.util.find_spec(\"clip\") else 1)" >/dev/null 2>&1'
        """.trimIndent().replace("\n", "; ")
    }

    private fun buildPidOrProcessStatusCommand(
        pidFile: String,
        port: Int,
        processPattern: String
    ): String {
        val safeProcessPattern = toSafePgrepPattern(processPattern)
        return """
            if [ -s $pidFile ]; then
              pgid=${'$'}(cat $pidFile)
              if [ -n "${'$'}pgid" ] && kill -0 -- -${'$'}pgid 2>/dev/null; then
                echo 'running'
              elif pgrep -f '$safeProcessPattern' >/dev/null 2>&1; then
                echo 'running'
              elif ${buildHttpProbeCommand(port)} >/dev/null 2>&1; then
                echo 'running'
              else
                rm -f $pidFile 2>/dev/null || true
                echo 'stopped'
              fi
            elif pgrep -f '$safeProcessPattern' >/dev/null 2>&1; then
              echo 'running'
            elif ${buildHttpProbeCommand(port)} >/dev/null 2>&1; then
              echo 'running'
            else
              echo 'stopped'
            fi
        """.trimIndent()
    }

    private fun toSafePgrepPattern(pattern: String): String {
        val index = pattern.indexOfFirst { !it.isWhitespace() }
        if (index == -1) return pattern

        val firstChar = pattern[index]
        return buildString {
            append(pattern.substring(0, index))
            append('[')
            append(firstChar)
            append(']')
            append(pattern.substring(index + 1))
        }
    }

    private fun getBindHost(networkVisible: Boolean): String = if (networkVisible) "0.0.0.0" else "127.0.0.1"

    private fun buildHttpProbeCommand(port: Int): String =
        "curl -s --connect-timeout 2 --max-time 5 http://127.0.0.1:$port/"

    private fun buildBigAgiLaunchCommand(networkVisible: Boolean): String {
        val host = getBindHost(networkVisible)
        val startCommand = "npx next start --port 8081 -H $host"
        return "bash -lc \"$startCommand || (rm -rf .next && npm run build && $startCommand)\""
    }

    // Uninstall all command
    const val UNINSTALL_ALL = "rm -rf ~/miniconda3 ~/big-AGI ~/text-generation-webui /home/fastsd /home/auto && userdel -r fastsd 2>/dev/null; userdel -r auto 2>/dev/null"

    // Individual uninstall commands
    fun getUninstallCommand(tool: TermuxTool): String {
        return when (tool.id) {
            "ollama" -> "apt remove ollama -y"
            "open_webui" -> "rm -rf ~/miniconda3/envs/webui"
            "big_agi" -> "rm -rf ~/big-AGI"
            "oobabooga" -> "rm -rf ~/text-generation-webui ~/miniconda3/envs/textgen"
            "fastsdcpu" -> "rm -rf ~/fastsdcpu ~/miniconda3/envs/fastsdcpu"
            "fastsdcpu_mcp" -> "echo 'MCP server uses same installation as fastsdcpu'"
            "a1111" -> "rm -rf ~/miniconda3/envs/a1111 && userdel -r auto || rm -rf /home/auto"
            else -> "echo 'Unknown tool'"
        }
    }

    /**
     * Get the run command for a tool based on network visibility setting
     * @param toolId The tool ID
     * @param networkVisible If true, bind to 0.0.0.0 (network accessible); if false, bind to localhost only
     */
    fun getRunCommand(toolId: String, networkVisible: Boolean): String {
        return when (toolId) {
            "ollama" -> if (networkVisible) {
                buildGuardedBackgroundRunCommand(
                    pidFile = OLLAMA_PID_FILE,
                    logFile = OLLAMA_LOG_FILE,
                    launchCommand = "env OLLAMA_HOST=${getBindHost(true)} ollama serve",
                    runningCondition = buildRunningCondition(OLLAMA_PID_FILE, Ports.OLLAMA, OLLAMA_PROCESS_PATTERN)
                )
            } else {
                buildGuardedBackgroundRunCommand(
                    pidFile = OLLAMA_PID_FILE,
                    logFile = OLLAMA_LOG_FILE,
                    launchCommand = "ollama serve",
                    runningCondition = buildRunningCondition(OLLAMA_PID_FILE, Ports.OLLAMA, OLLAMA_PROCESS_PATTERN)
                )
            }

            "open_webui" -> if (networkVisible) {
                buildGuardedBackgroundRunCommand(
                    pidFile = OPEN_WEBUI_PID_FILE,
                    logFile = OPEN_WEBUI_LOG_FILE,
                    launchCommand = "~/miniconda3/envs/webui/bin/python3 ~/miniconda3/envs/webui/bin/open-webui serve --port 8082 --host ${getBindHost(true)}",
                    runningCondition = buildRunningCondition(OPEN_WEBUI_PID_FILE, Ports.OPEN_WEBUI, OPEN_WEBUI_PROCESS_PATTERN)
                )
            } else {
                buildGuardedBackgroundRunCommand(
                    pidFile = OPEN_WEBUI_PID_FILE,
                    logFile = OPEN_WEBUI_LOG_FILE,
                    launchCommand = "~/miniconda3/envs/webui/bin/python3 ~/miniconda3/envs/webui/bin/open-webui serve --port 8082 --host ${getBindHost(false)}",
                    runningCondition = buildRunningCondition(OPEN_WEBUI_PID_FILE, Ports.OPEN_WEBUI, OPEN_WEBUI_PROCESS_PATTERN)
                )
            }

            "big_agi" -> if (networkVisible) {
                buildGuardedBackgroundRunCommand(
                    directory = "big-AGI",
                    pidFile = BIG_AGI_PID_FILE,
                    logFile = BIG_AGI_LOG_FILE,
                    launchCommand = buildBigAgiLaunchCommand(networkVisible = true),
                    runningCondition = buildRunningCondition(BIG_AGI_PID_FILE, Ports.BIG_AGI, BIG_AGI_PROCESS_PATTERN)
                )
            } else {
                buildGuardedBackgroundRunCommand(
                    directory = "big-AGI",
                    pidFile = BIG_AGI_PID_FILE,
                    logFile = BIG_AGI_LOG_FILE,
                    launchCommand = buildBigAgiLaunchCommand(networkVisible = false),
                    runningCondition = buildRunningCondition(BIG_AGI_PID_FILE, Ports.BIG_AGI, BIG_AGI_PROCESS_PATTERN)
                )
            }

            "oobabooga" -> if (networkVisible) {
                buildGuardedBackgroundRunCommand(
                    directory = "text-generation-webui",
                    pidFile = OOBABOOGA_PID_FILE,
                    logFile = OOBABOOGA_LOG_FILE,
                    launchCommand = "~/miniconda3/envs/textgen/bin/python3 server.py --cpu --listen-port 7861 --listen",
                    runningCondition = buildRunningCondition(OOBABOOGA_PID_FILE, Ports.OOBABOOGA, OOBABOOGA_PROCESS_PATTERN)
                )
            } else {
                buildGuardedBackgroundRunCommand(
                    directory = "text-generation-webui",
                    pidFile = OOBABOOGA_PID_FILE,
                    logFile = OOBABOOGA_LOG_FILE,
                    launchCommand = "~/miniconda3/envs/textgen/bin/python3 server.py --cpu --listen-port 7861",
                    runningCondition = buildRunningCondition(OOBABOOGA_PID_FILE, Ports.OOBABOOGA, OOBABOOGA_PROCESS_PATTERN)
                )
            }

            "fastsdcpu" -> if (networkVisible) {
                buildFastSdLaunchCommand(
                    pidFile = FASTSDCPU_WEBUI_PID_FILE,
                    logFile = FASTSDCPU_WEBUI_LOG_FILE,
                    scriptName = "start-webui.sh",
                    port = Ports.FASTSDCPU,
                    networkVisible = true
                )
            } else {
                buildFastSdLaunchCommand(
                    pidFile = FASTSDCPU_WEBUI_PID_FILE,
                    logFile = FASTSDCPU_WEBUI_LOG_FILE,
                    scriptName = "start-webui.sh",
                    port = Ports.FASTSDCPU,
                    networkVisible = false
                )
            }

            "fastsdcpu_mcp" -> if (networkVisible) {
                buildFastSdLaunchCommand(
                    pidFile = FASTSDCPU_MCP_PID_FILE,
                    logFile = FASTSDCPU_MCP_LOG_FILE,
                    scriptName = "start-mcpserver.sh",
                    port = Ports.FASTSDCPU_MCP,
                    networkVisible = true
                )
            } else {
                buildFastSdLaunchCommand(
                    pidFile = FASTSDCPU_MCP_PID_FILE,
                    logFile = FASTSDCPU_MCP_LOG_FILE,
                    scriptName = "start-mcpserver.sh",
                    port = Ports.FASTSDCPU_MCP,
                    networkVisible = false
                )
            }

            "a1111" -> buildA1111RunCommand(networkVisible)

            else -> "echo 'Unknown tool'"
        }
    }

    fun getStopCommand(toolId: String): String {
        return when (toolId) {
            "fastsdcpu" -> buildFastSdStopCommand(
                pidFile = FASTSDCPU_WEBUI_PID_FILE,
                scriptPattern = FASTSDCPU_WEBUI_SCRIPT_PATTERN,
                appPattern = FASTSDCPU_WEBUI_PROCESS_PATTERN,
                port = Ports.FASTSDCPU
            )
            "fastsdcpu_mcp" -> buildFastSdStopCommand(
                pidFile = FASTSDCPU_MCP_PID_FILE,
                scriptPattern = FASTSDCPU_MCP_SCRIPT_PATTERN,
                appPattern = FASTSDCPU_MCP_PROCESS_PATTERN,
                port = Ports.FASTSDCPU_MCP
            )
            "big_agi" -> buildBigAgiStopCommand()
            else -> getTool(toolId)?.stopCommand ?: "echo 'Unknown tool'"
        }
    }

    fun getStatusCommand(toolId: String): String {
        return when (toolId) {
            "ollama" -> buildPidOrProcessStatusCommand(OLLAMA_PID_FILE, Ports.OLLAMA, OLLAMA_PROCESS_PATTERN)
            "open_webui" -> buildPidOrProcessStatusCommand(OPEN_WEBUI_PID_FILE, Ports.OPEN_WEBUI, OPEN_WEBUI_PROCESS_PATTERN)
            "big_agi" -> buildPidOrProcessStatusCommand(BIG_AGI_PID_FILE, Ports.BIG_AGI, BIG_AGI_PROCESS_PATTERN)
            "oobabooga" -> buildPidOrProcessStatusCommand(OOBABOOGA_PID_FILE, Ports.OOBABOOGA, OOBABOOGA_PROCESS_PATTERN)
            "fastsdcpu" -> buildFastSdStatusCommand(FASTSDCPU_WEBUI_PID_FILE, Ports.FASTSDCPU, FASTSDCPU_WEBUI_PROCESS_PATTERN)
            "fastsdcpu_mcp" -> buildFastSdStatusCommand(FASTSDCPU_MCP_PID_FILE, Ports.FASTSDCPU_MCP, FASTSDCPU_MCP_PROCESS_PATTERN)
            "a1111" -> buildPidOrProcessStatusCommand(
                pidFile = "/home/auto/a1111.pid",
                port = Ports.A1111,
                processPattern = A1111_LAUNCH_PATTERN
            )
            else -> getTool(toolId)?.takeIf { it.port > 0 }?.let { buildHttpStatusCommand(it.port) }
                ?: "echo 'stopped'"
        }
    }

    fun getLogFile(toolId: String): String? {
        return when (toolId) {
            "ollama" -> OLLAMA_LOG_FILE
            "open_webui" -> OPEN_WEBUI_LOG_FILE
            "big_agi" -> BIG_AGI_LOG_FILE
            "oobabooga" -> OOBABOOGA_LOG_FILE
            "fastsdcpu" -> FASTSDCPU_WEBUI_LOG_FILE
            "fastsdcpu_mcp" -> FASTSDCPU_MCP_LOG_FILE
            "a1111" -> "/home/auto/a1111.log"
            else -> null
        }
    }

    /**
     * Get the pre-run command for A1111 to enable/disable network visibility
     * This modifies webui-user.sh using sed before starting the server
     * @param networkVisible If true, adds --listen flag; if false, removes it
     * @return The sed command to run before starting A1111, or null if not A1111
     */
    fun getA1111VisibilityCommand(networkVisible: Boolean): String {
        val commandLineArgs = buildA1111CommandLineArgs(networkVisible)
        return """su - auto -c 'if grep -q "^export COMMANDLINE_ARGS=" ~/stable-diffusion-webui/webui-user.sh; then sed -i "s|^export COMMANDLINE_ARGS=.*|export COMMANDLINE_ARGS=\"$commandLineArgs\"|" ~/stable-diffusion-webui/webui-user.sh; else echo "export COMMANDLINE_ARGS=\"$commandLineArgs\"" >> ~/stable-diffusion-webui/webui-user.sh; fi'"""
    }

    private fun buildA1111LaunchCommand(): String {
        return """rm -f /home/auto/a1111.pid /home/auto/a1111.log && setsid -f su - auto -c 'cd ~/stable-diffusion-webui && pgid=${'$'}(ps -o pgid= ${'$'}${'$'} | tr -d " ") && echo ${'$'}pgid > ~/a1111.pid && exec ./webui.sh > ~/a1111.log 2>&1 < /dev/null'"""
    }

    private fun buildA1111RunCommand(networkVisible: Boolean): String {
        return """
            ${buildA1111ClipEnsureCommand()}
            ${getA1111VisibilityCommand(networkVisible)}
            if ${buildRunningCondition("/home/auto/a1111.pid", Ports.A1111, A1111_LAUNCH_PATTERN)}; then
              echo 'already running'
            else
              ${buildA1111LaunchCommand()}
            fi
        """.trimIndent()
    }

    private fun buildA1111CommandLineArgs(networkVisible: Boolean): String {
        val listenFlag = if (networkVisible) "--listen " else ""
        return "--port 7865 ${listenFlag}--api --use-cpu all --precision full --no-half --skip-torch-cuda-test --skip-load-model-at-start --no-download-sd-model --skip-prepare-environment"
    }

    private fun buildA1111StopCommand(): String {
        val safeWebUiPattern = toSafePgrepPattern("webui.sh")
        val safeLaunchPattern = toSafePgrepPattern(A1111_LAUNCH_PATTERN)
        return """
            su - auto -c 'if [ -s ~/a1111.pid ]; then pgid=${'$'}(cat ~/a1111.pid); if [ -n "${'$'}pgid" ]; then kill -- -${'$'}pgid 2>/dev/null || true; sleep 2; if kill -0 -- -${'$'}pgid 2>/dev/null; then kill -9 -- -${'$'}pgid 2>/dev/null || true; fi; fi; rm -f ~/a1111.pid; fi'
            pkill -9 -f '$safeWebUiPattern' 2>/dev/null || true
            pkill -9 -f '$safeLaunchPattern' 2>/dev/null || true
            ${buildPortOwnerKillBlock(Ports.A1111)}
        """.trimIndent()
    }

    private fun buildProcessGroupStopBlock(pidFile: String): String {
        return """
            if [ -s $pidFile ]; then
              pgid=${'$'}(cat $pidFile)
              if [ -n "${'$'}pgid" ]; then
                kill -- -${'$'}pgid 2>/dev/null || true
                sleep 2
                if kill -0 -- -${'$'}pgid 2>/dev/null; then
                  kill -9 -- -${'$'}pgid 2>/dev/null || true
                fi
              fi
              rm -f $pidFile
            fi
        """.trimIndent()
    }

    private fun buildPortOwnerKillBlock(port: Int): String {
        return """
            for pid in ${'$'}(ss -ltnpH 2>/dev/null | awk '${'$'}4 ~ /:$port$/ {print ${'$'}NF}' | grep -o 'pid=[0-9]*' | cut -d= -f2 | sort -u); do
              kill -9 "${'$'}pid" 2>/dev/null || true
            done
            for pid in ${'$'}(lsof -ti tcp:$port -sTCP:LISTEN 2>/dev/null | sort -u); do
              kill -9 "${'$'}pid" 2>/dev/null || true
            done
            fuser -k $port/tcp 2>/dev/null || true
        """.trimIndent()
    }
}
