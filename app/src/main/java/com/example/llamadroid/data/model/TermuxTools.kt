package com.example.llamadroid.data.model

import com.example.llamadroid.util.AIConstants

/**
 * Termux Doomsday Tools - install scripts for proot-distro
 * Based on https://github.com/ManuXD32/Termux-doomsday-LLM
 * 
 * NOTE: These commands run INSIDE a proot distro (via SSH on port 8022)
 * The user SSHs into a Debian proot that has an SSH server running.
 * Commands adapted from installer.sh for Debian (not Ubuntu proot).
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
    val runCommand: String,
    val stopCommand: String,
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
        githubUrl = "https://github.com/enricoros/big-AGI"
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
        githubUrl = "https://github.com/oobabooga/text-generation-webui"
    )
    
    val fastsdcpu = ToolInfo(
        ramRequirement = "2-5GB (SD Turbo + TAESD)",
        lowRamTips = listOf(
            "Use SD Turbo or SDXS-512 models (1 step, fastest)",
            "Enable TAESD (Tiny Decoder) - saves ~2GB RAM",
            "Use 256x256 or 512x512 resolution",
            "Set guidance_scale=1 (higher uses more RAM)"
        ),
        integration = "MCP Server: python src/app.py --mcp (port 8000). OpenWebUI: add http://127.0.0.1:8000 in Settings, set Function Calling to Native.",
        features = listOf("Desktop GUI, Web UI, CLI", "OpenVINO support", "LCM-LoRA", "ControlNet", "Real-time generation", "Raspberry Pi + Android support"),
        githubUrl = "https://github.com/rupeshs/fastsdcpu"
    )
    
    val a1111 = ToolInfo(
        ramRequirement = "8GB+ minimum",
        lowRamTips = listOf(
            "⚠️ NOT recommended for low RAM",
            "Use FastSD CPU instead - faster and works on Android",
            "If needed: use --lowvram or --medvram flags"
        ),
        integration = "⚠️ Currently broken due to GitHub auth issues during install. Use FastSD CPU as alternative.",
        features = listOf("Stable Diffusion WebUI", "Extensions", "ControlNet", "LoRA support"),
        githubUrl = "https://github.com/AUTOMATIC1111/stable-diffusion-webui"
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
 * All available Termux tools - commands run inside proot via SSH
 */
object TermuxTools {
    
    // Default SSH port for proot Debian
    const val DEFAULT_SSH_PORT = 8022
    
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

    private const val FASTSDCPU_WEBUI_PID_FILE = "/tmp/fastsdcpu-webui.pid"
    private const val FASTSDCPU_MCP_PID_FILE = "/tmp/fastsdcpu-mcp.pid"
    private const val FASTSDCPU_WEBUI_LOG_FILE = "/tmp/fastsdcpu-webui.log"
    private const val FASTSDCPU_MCP_LOG_FILE = "/tmp/fastsdcpu-mcp.log"
    
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
        runCommand = "ollama serve &",
        stopCommand = "pkill ollama",
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
        runCommand = "~/miniconda3/envs/webui/bin/python3 ~/miniconda3/envs/webui/bin/open-webui serve --port 8082 &",
        stopCommand = "pkill -f open-webui"
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
            "git clone https://github.com/enricoros/big-AGI.git",
            "cd big-AGI && npm install -g npm@11.0.0 && npm install && npm run build"
        ),
        runCommand = "cd big-AGI && npx next start --port 8081 -H 0.0.0.0 &",
        stopCommand = "pkill -f 'next start'"
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
            "git clone https://github.com/oobabooga/text-generation-webui",
            "cd text-generation-webui && ~/miniconda3/envs/textgen/bin/pip3 install -r requirements/full/requirements_cpu_only_noavx2.txt"
        ),
        runCommand = "cd text-generation-webui && ~/miniconda3/envs/textgen/bin/python3 server.py --cpu --listen-port 7861 &",
        stopCommand = "pkill -f 'server.py'"
    )
    
    // FastSD CPU - uses miniconda with modified install.sh (tested working approach)
    val fastsdcpu = TermuxTool(
        id = "fastsdcpu",
        name = "FastSD CPU",
        emoji = "🎨",
        descriptionResId = com.example.llamadroid.R.string.tool_fastsdcpu_desc,
        port = Ports.FASTSDCPU,
        installPath = "~/fastsdcpu",
        modelsPath = ModelPaths.FASTSDCPU,
        requiresMiniconda = true,
        installCommands = listOf(
            // Create conda environment with Python 3.11
            "~/miniconda3/bin/conda create -n fastsdcpu python=3.11 -y",
            // Install dependencies and clone repo
            "apt update && apt install ffmpeg git -y && git clone https://github.com/rupeshs/fastsdcpu.git",
            // Remove uv venv line from install.sh
            "sed -i -e '/uv venv --python 3.11.6/d' /root/fastsdcpu/install.sh",
            // Remove source activate line from install.sh
            """sed -i -e '/source "${'$'}BASEDIR\/env\/bin\/activate"/d' /root/fastsdcpu/install.sh""",
            // Remove uv command
            "sed -i 's/\\buv\\b//g' /root/fastsdcpu/install.sh",
            // Change version from 3.11.6 to 3.11
            "sed -i -e 's/3.11.6/3.11/g' /root/fastsdcpu/install.sh",
            // Remove PyQt5 from requirements
            "sed -i '/^PyQt5\\b/d' /root/fastsdcpu/requirements.txt",
            // Change mediapipe to unpinned version
            "sed -i 's/^mediapipe==.*$/mediapipe/' /root/fastsdcpu/requirements.txt",
            // Set PYTHON_COMMAND to conda python in install.sh
            """sed -i 's|PYTHON_COMMAND="python3"|PYTHON_COMMAND="/root/miniconda3/envs/fastsdcpu/bin/python3"|g' /root/fastsdcpu/install.sh""",
            """sed -i 's|PYTHON_COMMAND="python"|PYTHON_COMMAND="/root/miniconda3/envs/fastsdcpu/bin/python3"|g' /root/fastsdcpu/install.sh""",
            // Remove venv creation lines from install.sh
            """sed -i '/${'$'}PYTHON_COMMAND -m venv "${'$'}BASEDIR/env"/,+2d' /root/fastsdcpu/install.sh""",
            // Replace pip with full path in install.sh
            """sed -i 's| pip | /root/miniconda3/envs/fastsdcpu/bin/pip |g' /root/fastsdcpu/install.sh""",
            // Remove source activate from start-webui.sh
            """sed -i '/source "${'$'}BASEDIR\/env\/bin\/activate"/d' /root/fastsdcpu/start-webui.sh""",
            // Set PYTHON_COMMAND to conda python in start-webui.sh
            """sed -i 's|PYTHON_COMMAND="python3"|PYTHON_COMMAND="/root/miniconda3/envs/fastsdcpu/bin/python3"|g' /root/fastsdcpu/start-webui.sh""",
            """sed -i 's|PYTHON_COMMAND="python"|PYTHON_COMMAND="/root/miniconda3/envs/fastsdcpu/bin/python3"|g' /root/fastsdcpu/start-webui.sh""",
            // Run install script
            "cd fastsdcpu && chmod +x install.sh && ./install.sh",
            // Create MCP server script by copying start-webui.sh
            "rm -f /root/fastsdcpu/start-mcpserver.sh && cp /root/fastsdcpu/start-webui.sh /root/fastsdcpu/start-mcpserver.sh",
            // Change -w to --mcp in the MCP server script
            """sed -i 's|${'$'}PYTHON_COMMAND src/app.py -w|${'$'}PYTHON_COMMAND src/app.py --mcp|g' /root/fastsdcpu/start-mcpserver.sh"""
        ),
        runCommand = buildBackgroundRunCommand(
            directory = "fastsdcpu",
            pidFile = FASTSDCPU_WEBUI_PID_FILE,
            logFile = FASTSDCPU_WEBUI_LOG_FILE,
            launchCommand = "bash start-webui.sh"
        ),
        stopCommand = buildFastSdStopCommand(
            pidFile = FASTSDCPU_WEBUI_PID_FILE,
            scriptPattern = "start-webui.sh",
            appPattern = "src/app.py -w",
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
        installCommands = emptyList(),  // Uses same install as fastsdcpu
        runCommand = buildBackgroundRunCommand(
            directory = "fastsdcpu",
            pidFile = FASTSDCPU_MCP_PID_FILE,
            logFile = FASTSDCPU_MCP_LOG_FILE,
            launchCommand = "bash start-mcpserver.sh"
        ),
        stopCommand = buildFastSdStopCommand(
            pidFile = FASTSDCPU_MCP_PID_FILE,
            scriptPattern = "start-mcpserver.sh",
            appPattern = "src/app.py --mcp",
            port = Ports.FASTSDCPU_MCP
        ),
        hasWebUI = false  // MCP is API-only, no web UI
    )
    
    // Automatic 1111 - uses conda Python 3.10 (auto user)
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
            // Create conda environment with Python 3.10 for auto user
            "~/miniconda3/bin/conda create -n a1111 python=3.10 -y",
            // Clone repo as user auto
            "su - auto -c 'git clone https://github.com/AUTOMATIC1111/stable-diffusion-webui.git'",
            // Pre-clone required repositories to avoid authentication errors during first run
            // These repos are cloned by launch.py but may fail if network has issues
            "su - auto -c 'mkdir -p ~/stable-diffusion-webui/repositories'",
            "su - auto -c 'git clone https://github.com/Stability-AI/stablediffusion.git ~/stable-diffusion-webui/repositories/stable-diffusion-stability-ai' || su - auto -c 'git clone https://huggingface.co/stabilityai/stable-diffusion-2-1 ~/stable-diffusion-webui/repositories/stable-diffusion-stability-ai --depth 1' || true",
            "su - auto -c 'git clone https://github.com/CompVis/taming-transformers.git ~/stable-diffusion-webui/repositories/taming-transformers' || true",
            "su - auto -c 'git clone https://github.com/crowsonkb/k-diffusion.git ~/stable-diffusion-webui/repositories/k-diffusion' || true",
            "su - auto -c 'git clone https://github.com/sczhou/CodeFormer.git ~/stable-diffusion-webui/repositories/CodeFormer' || true",
            "su - auto -c 'git clone https://github.com/salesforce/BLIP.git ~/stable-diffusion-webui/repositories/BLIP' || true",
            // Delete existing venv (will pre-create with conda Python)
            "su - auto -c 'rm -rf ~/stable-diffusion-webui/venv'",
            // Pre-create venv with conda Python 3.10
            "su - auto -c '/root/miniconda3/envs/a1111/bin/python -m venv ~/stable-diffusion-webui/venv'",
            // Configure webui-user.sh with conda Python
            "su - auto -c 'sed -i \"/#python_cmd/d\" ~/stable-diffusion-webui/webui-user.sh'",
            "su - auto -c 'sed -i \"/#export COMMANDLINE_ARGS/d\" ~/stable-diffusion-webui/webui-user.sh'",
            "su - auto -c 'echo \"python_cmd=\\\"/root/miniconda3/envs/a1111/bin/python\\\"\" >> ~/stable-diffusion-webui/webui-user.sh'",
            "su - auto -c 'echo \"export COMMANDLINE_ARGS=\\\"--port 7865 --api --use-cpu all --precision full --no-half --skip-torch-cuda-test --skip-load-model-at-start\\\"\" >> ~/stable-diffusion-webui/webui-user.sh'"
        ),
        runCommand = "su - auto -c 'cd ~/stable-diffusion-webui && ./webui.sh' &",
        stopCommand = "pkill -f 'webui.sh'"
    )
    
    // All tools list (AI Agent is separate - see AIHubScreen)
    val allTools = listOf(ollama, openWebUI, bigAGI, oobabooga, fastsdcpu, fastsdcpuMcp, automatic1111)
    
    fun getTool(id: String): TermuxTool? = allTools.find { it.id == id }
    
    // Tools that require Miniconda
    val toolsRequiringMiniconda = allTools.filter { it.requiresMiniconda }
    
    // Tools with model management
    val toolsWithModels = allTools.filter { it.modelsPath.isNotEmpty() }
    
    // Base setup command
    const val BASE_SETUP = "apt update && apt upgrade -y && apt install curl wget git -y"

    private fun buildBackgroundRunCommand(
        directory: String,
        pidFile: String,
        logFile: String,
        launchCommand: String
    ): String {
        return "cd $directory && rm -f $pidFile && nohup $launchCommand > $logFile 2>&1 < /dev/null & echo ${'$'}! > $pidFile"
    }

    private fun buildFastSdStopCommand(
        pidFile: String,
        scriptPattern: String,
        appPattern: String,
        port: Int
    ): String {
        return """
            if [ -s $pidFile ]; then
              pid=${'$'}(cat $pidFile)
              if [ -n "${'$'}pid" ]; then
                kill "${'$'}pid" 2>/dev/null || true
                sleep 2
                kill -9 "${'$'}pid" 2>/dev/null || true
              fi
              rm -f $pidFile
            fi
            pkill -9 -f '$scriptPattern' 2>/dev/null || true
            pkill -9 -f '$appPattern' 2>/dev/null || true
            fuser -k $port/tcp 2>/dev/null || true
        """.trimIndent().replace("\n", "; ")
    }

    private fun buildHttpStatusCommand(port: Int): String {
        return "curl -s --connect-timeout 2 http://127.0.0.1:$port/ >/dev/null 2>&1 && echo 'running' || echo 'stopped'"
    }

    private fun buildFastSdStatusCommand(pidFile: String, port: Int): String {
        return """
            if [ -s $pidFile ]; then
              pid=${'$'}(cat $pidFile)
              if [ -n "${'$'}pid" ] && kill -0 "${'$'}pid" 2>/dev/null; then
                echo 'running'
              elif curl -s --connect-timeout 2 http://127.0.0.1:$port/ >/dev/null 2>&1; then
                echo 'running'
              else
                rm -f $pidFile 2>/dev/null || true
                echo 'stopped'
              fi
            elif curl -s --connect-timeout 2 http://127.0.0.1:$port/ >/dev/null 2>&1; then
              echo 'running'
            else
              echo 'stopped'
            fi
        """.trimIndent().replace("\n", "; ")
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
                "export OLLAMA_HOST=0.0.0.0 && ollama serve &"
            } else {
                "ollama serve &"
            }
            
            "open_webui" -> if (networkVisible) {
                "~/miniconda3/envs/webui/bin/python3 ~/miniconda3/envs/webui/bin/open-webui serve --port 8082 --host 0.0.0.0 &"
            } else {
                "~/miniconda3/envs/webui/bin/python3 ~/miniconda3/envs/webui/bin/open-webui serve --port 8082 --host 127.0.0.1 &"
            }
            
            "big_agi" -> if (networkVisible) {
                "cd big-AGI && npx next start --port 8081 -H 0.0.0.0 &"
            } else {
                "cd big-AGI && npx next start --port 8081 -H 127.0.0.1 &"
            }
            
            "oobabooga" -> if (networkVisible) {
                "cd text-generation-webui && ~/miniconda3/envs/textgen/bin/python3 server.py --cpu --listen-port 7861 --listen &"
            } else {
                "cd text-generation-webui && ~/miniconda3/envs/textgen/bin/python3 server.py --cpu --listen-port 7861 &"
            }
            
            "fastsdcpu" -> if (networkVisible) {
                buildBackgroundRunCommand(
                    directory = "fastsdcpu",
                    pidFile = FASTSDCPU_WEBUI_PID_FILE,
                    logFile = FASTSDCPU_WEBUI_LOG_FILE,
                    launchCommand = "GRADIO_SERVER_NAME=0.0.0.0 bash start-webui.sh"
                )
            } else {
                buildBackgroundRunCommand(
                    directory = "fastsdcpu",
                    pidFile = FASTSDCPU_WEBUI_PID_FILE,
                    logFile = FASTSDCPU_WEBUI_LOG_FILE,
                    launchCommand = "bash start-webui.sh"
                )
            }
            
            "fastsdcpu_mcp" -> buildBackgroundRunCommand(
                directory = "fastsdcpu",
                pidFile = FASTSDCPU_MCP_PID_FILE,
                logFile = FASTSDCPU_MCP_LOG_FILE,
                launchCommand = "bash start-mcpserver.sh"
            )
            
            "a1111" -> {
                // A1111 uses webui-user.sh, run command stays the same
                // The sed pre-command handles the --listen flag
                "su - auto -c 'cd ~/stable-diffusion-webui && ./webui.sh' &"
            }
            
            else -> "echo 'Unknown tool'"
        }
    }

    fun getStopCommand(toolId: String): String {
        return when (toolId) {
            "fastsdcpu" -> buildFastSdStopCommand(
                pidFile = FASTSDCPU_WEBUI_PID_FILE,
                scriptPattern = "start-webui.sh",
                appPattern = "src/app.py -w",
                port = Ports.FASTSDCPU
            )
            "fastsdcpu_mcp" -> buildFastSdStopCommand(
                pidFile = FASTSDCPU_MCP_PID_FILE,
                scriptPattern = "start-mcpserver.sh",
                appPattern = "src/app.py --mcp",
                port = Ports.FASTSDCPU_MCP
            )
            else -> getTool(toolId)?.stopCommand ?: "echo 'Unknown tool'"
        }
    }

    fun getStatusCommand(toolId: String): String {
        return when (toolId) {
            "fastsdcpu" -> buildFastSdStatusCommand(FASTSDCPU_WEBUI_PID_FILE, Ports.FASTSDCPU)
            "fastsdcpu_mcp" -> buildFastSdStatusCommand(FASTSDCPU_MCP_PID_FILE, Ports.FASTSDCPU_MCP)
            else -> getTool(toolId)?.takeIf { it.port > 0 }?.let { buildHttpStatusCommand(it.port) }
                ?: "echo 'stopped'"
        }
    }
    
    /**
     * Get the pre-run command for A1111 to enable/disable network visibility
     * This modifies webui-user.sh using sed before starting the server
     * @param networkVisible If true, adds --listen flag; if false, removes it
     * @return The sed command to run before starting A1111, or null if not A1111
     */
    fun getA1111VisibilityCommand(networkVisible: Boolean): String {
        return if (networkVisible) {
            // Add --listen flag (only if not already present)
            """su - auto -c 'sed -i "s/COMMANDLINE_ARGS=\"--port 7865 --api/COMMANDLINE_ARGS=\"--port 7865 --listen --api/" ~/stable-diffusion-webui/webui-user.sh'"""
        } else {
            // Remove --listen flag
            """su - auto -c 'sed -i "s/COMMANDLINE_ARGS=\"--port 7865 --listen --api/COMMANDLINE_ARGS=\"--port 7865 --api/" ~/stable-diffusion-webui/webui-user.sh'"""
        }
    }
}
