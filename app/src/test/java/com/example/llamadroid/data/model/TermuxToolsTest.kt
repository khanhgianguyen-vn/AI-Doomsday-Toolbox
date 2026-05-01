package com.example.llamadroid.data.model

import com.example.llamadroid.service.SSHService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxToolsTest {

    @Test
    fun `termux ssh defaults now use port 8025`() {
        assertEquals(8025, TermuxTools.DEFAULT_SSH_PORT)
        assertEquals(8025, SSHService.DEFAULT_PORT)
    }

    @Test
    fun `installer commands stay inside the ubuntu ssh shell`() {
        val installCommands = buildList {
            addAll(TermuxTools.MINICONDA_INSTALL_COMMANDS)
            TermuxTools.allTools.forEach { addAll(it.installCommands) }
        }

        assertFalse(installCommands.any { it.contains("pkg ") })
        assertFalse(installCommands.any { it.contains("pd login") })
        assertFalse(installCommands.any { it.contains("proot-distro login") })
        assertFalse(installCommands.any { it.contains("requirements_cpu_only_noavx2.txt") })
    }

    @Test
    fun `open webui run command detaches and respects host binding`() {
        val localCommand = TermuxTools.getRunCommand("open_webui", networkVisible = false)
        val lanCommand = TermuxTools.getRunCommand("open_webui", networkVisible = true)

        assertTrue(localCommand.contains("already running"))
        assertTrue(localCommand.contains("setsid -f bash -lc"))
        assertTrue(localCommand.contains("/tmp/open-webui.pid"))
        assertTrue(localCommand.contains("ps -o pgid="))
        assertTrue(localCommand.contains("--host 127.0.0.1"))

        assertTrue(lanCommand.contains("already running"))
        assertTrue(lanCommand.contains("setsid -f bash -lc"))
        assertTrue(lanCommand.contains("/tmp/open-webui.pid"))
        assertTrue(lanCommand.contains("ps -o pgid="))
        assertTrue(lanCommand.contains("--host 0.0.0.0"))
    }

    @Test
    fun `ollama run command detaches and keeps lan override explicit`() {
        val localCommand = TermuxTools.getRunCommand("ollama", networkVisible = false)
        val lanCommand = TermuxTools.getRunCommand("ollama", networkVisible = true)

        assertTrue(localCommand.contains("already running"))
        assertTrue(localCommand.contains("setsid -f bash -lc"))
        assertTrue(localCommand.contains("/tmp/ollama.pid"))
        assertTrue(localCommand.contains("ps -o pgid="))
        assertFalse(localCommand.contains("OLLAMA_HOST=0.0.0.0"))

        assertTrue(lanCommand.contains("already running"))
        assertTrue(lanCommand.contains("setsid -f bash -lc"))
        assertTrue(lanCommand.contains("/tmp/ollama.pid"))
        assertTrue(lanCommand.contains("ps -o pgid="))
        assertTrue(lanCommand.contains("env OLLAMA_HOST=0.0.0.0 ollama serve"))
    }

    @Test
    fun `forked repos back installers and visible tool links`() {
        val bigAgiCommands = TermuxTools.getTool("big_agi")!!.installCommands.joinToString("\n")
        val oobaboogaCommands = TermuxTools.getTool("oobabooga")!!.installCommands.joinToString("\n")
        val fastSdCommands = TermuxTools.getTool("fastsdcpu")!!.installCommands.joinToString("\n")
        val a1111Commands = TermuxTools.getTool("a1111")!!.installCommands.joinToString("\n")

        assertTrue(bigAgiCommands.contains("git clone --branch v2-dev --single-branch https://github.com/ManuXD32/big-AGI.git"))
        assertTrue(oobaboogaCommands.contains("git clone https://github.com/ManuXD32/textgen text-generation-webui"))
        assertTrue(fastSdCommands.contains("git clone https://github.com/ManuXD32/fastsdcpu"))
        assertTrue(a1111Commands.contains("git clone https://github.com/ManuXD32/stable-diffusion-webui.git"))
        assertTrue(ToolInfoCards.bigAGI.githubUrl.contains("ManuXD32/big-AGI/tree/v2-dev"))
        assertTrue(ToolInfoCards.oobabooga.githubUrl.contains("ManuXD32/textgen"))
        assertTrue(ToolInfoCards.fastsdcpu.githubUrl.contains("ManuXD32/fastsdcpu"))
        assertTrue(ToolInfoCards.a1111.githubUrl.contains("ManuXD32/stable-diffusion-webui"))
    }

    @Test
    fun `fastsd install commands keep upstream uv installer and android safe link mode`() {
        val commands = TermuxTools.getTool("fastsdcpu")!!.installCommands.joinToString("\n")

        assertTrue(commands.contains("curl -LsSf https://astral.sh/uv/install.sh | sh"))
        assertTrue(commands.contains("UV_LINK_MODE=copy"))
        assertTrue(commands.contains("./install.sh --disable-gui"))
        assertTrue(commands.contains("torch==2.4.1 torchvision==0.19.1"))
        assertTrue(
            commands.contains(
                "sed -i 's|torch==2.8.0 torchvision==0.23.0|torch==2.4.1 torchvision==0.19.1|g' /root/fastsdcpu/install.sh"
            )
        )
        assertFalse(commands.contains("sed -i 's/\\\\buv\\\\b//g'"))
    }

    @Test
    fun `fastsd mcp hides its install card and install all keeps shared install single pass`() {
        val mcpTool = TermuxTools.getTool("fastsdcpu_mcp")!!
        val fastSdTool = TermuxTools.getTool("fastsdcpu")!!

        assertFalse(mcpTool.showInstallCard)
        assertFalse(TermuxTools.installableTools.any { it.id == "fastsdcpu_mcp" })
        assertEquals(fastSdTool.installCheckCommand, mcpTool.installCheckCommand)
        assertEquals(
            TermuxTools.MINICONDA_INSTALL_COMMANDS.size + TermuxTools.installableTools.sumOf { it.installCommands.size },
            TermuxTools.installAllCommands.size
        )
    }

    @Test
    fun `every termux tool exposes a non empty install check command`() {
        TermuxTools.allTools.forEach { tool ->
            assertTrue(tool.installCheckCommand.isNotBlank())
            assertTrue(tool.installCheckCommand.contains("echo 'installed'"))
            assertTrue(tool.installCheckCommand.contains("echo 'not'"))
        }
    }

    @Test
    fun `fastsd web ui and mcp keep separate ports pid files and lan bindings`() {
        val localWebUiCommand = TermuxTools.getRunCommand("fastsdcpu", networkVisible = false)
        val lanWebUiCommand = TermuxTools.getRunCommand("fastsdcpu", networkVisible = true)
        val localMcpCommand = TermuxTools.getRunCommand("fastsdcpu_mcp", networkVisible = false)
        val lanMcpCommand = TermuxTools.getRunCommand("fastsdcpu_mcp", networkVisible = true)
        val webUiStatus = TermuxTools.getStatusCommand("fastsdcpu")
        val mcpStatus = TermuxTools.getStatusCommand("fastsdcpu_mcp")

        assertTrue(localWebUiCommand.contains("/tmp/fastsdcpu-webui.pid"))
        assertTrue(localWebUiCommand.contains("ps -o pgid="))
        assertTrue(localWebUiCommand.contains("GRADIO_SERVER_NAME=127.0.0.1"))
        assertTrue(localWebUiCommand.contains("GRADIO_SERVER_PORT=7860"))
        assertTrue(lanWebUiCommand.contains("GRADIO_SERVER_NAME=0.0.0.0"))
        assertTrue(localMcpCommand.contains("/tmp/fastsdcpu-mcp.pid"))
        assertTrue(localMcpCommand.contains("ps -o pgid="))
        assertTrue(localMcpCommand.contains("GRADIO_SERVER_NAME=127.0.0.1"))
        assertTrue(localMcpCommand.contains("GRADIO_SERVER_PORT=8000"))
        assertTrue(lanMcpCommand.contains("GRADIO_SERVER_NAME=0.0.0.0"))
        assertTrue(webUiStatus.contains("[s]rc/app.py -w"))
        assertTrue(mcpStatus.contains("[s]rc/app.py --mcp"))
    }

    @Test
    fun `generic status commands honor pid files during slow startup`() {
        val openWebUiStatus = TermuxTools.getStatusCommand("open_webui")
        val bigAgiStatus = TermuxTools.getStatusCommand("big_agi")
        val oobaboogaStatus = TermuxTools.getStatusCommand("oobabooga")
        val a1111Status = TermuxTools.getStatusCommand("a1111")

        assertTrue(openWebUiStatus.contains("/tmp/open-webui.pid"))
        assertTrue(openWebUiStatus.contains("[o]pen-webui serve --port 8082"))
        assertTrue(openWebUiStatus.contains("--max-time 5"))
        assertTrue(bigAgiStatus.contains("/tmp/big-agi.pid"))
        assertTrue(bigAgiStatus.contains("[n]ext start --port 8081"))
        assertTrue(oobaboogaStatus.contains("[s]erver.py --cpu --listen-port 7861"))
        assertTrue(a1111Status.contains("/home/auto/a1111.pid"))
        assertTrue(a1111Status.contains("[s]table-diffusion-webui/launch.py"))
    }

    @Test
    fun `big agi run command repairs broken next build artifacts before retrying`() {
        val localCommand = TermuxTools.getRunCommand("big_agi", networkVisible = false)
        val lanCommand = TermuxTools.getRunCommand("big_agi", networkVisible = true)

        assertTrue(localCommand.contains("npx next start --port 8081 -H 127.0.0.1 || (rm -rf .next && npm run build && npx next start --port 8081 -H 127.0.0.1)"))
        assertTrue(lanCommand.contains("npx next start --port 8081 -H 0.0.0.0 || (rm -rf .next && npm run build && npx next start --port 8081 -H 0.0.0.0)"))
    }

    @Test
    fun `stop commands stop process groups before narrow fallbacks`() {
        val openWebUiStop = TermuxTools.getStopCommand("open_webui")
        val bigAgiStop = TermuxTools.getStopCommand("big_agi")
        val oobaboogaStop = TermuxTools.getStopCommand("oobabooga")
        val fastSdStop = TermuxTools.getStopCommand("fastsdcpu")
        val fastSdMcpStop = TermuxTools.getStopCommand("fastsdcpu_mcp")
        val a1111Stop = TermuxTools.getStopCommand("a1111")

        assertTrue(openWebUiStop.contains("kill -- -\$pgid"))
        assertTrue(openWebUiStop.indexOf("kill -- -\$pgid") < openWebUiStop.indexOf("[o]pen-webui serve --port 8082"))
        assertTrue(openWebUiStop.contains("[o]pen-webui serve --port 8082"))
        assertTrue(openWebUiStop.contains("ss -ltnpH"))
        assertTrue(openWebUiStop.contains("lsof -ti tcp:8082"))
        assertTrue(bigAgiStop.contains("kill -- -\$pgid"))
        assertTrue(bigAgiStop.contains("[n]ext start --port 8081"))
        assertTrue(bigAgiStop.contains("[n]ode /root/big-AGI/node_modules/.bin/next build"))
        assertTrue(bigAgiStop.contains("[n]pm run build"))
        assertTrue(oobaboogaStop.contains("kill -- -\$pgid"))
        assertTrue(oobaboogaStop.contains("[s]erver.py --cpu --listen-port 7861"))
        assertTrue(fastSdStop.contains("kill -- -\$pgid"))
        assertTrue(fastSdStop.contains("[b]ash start-webui.sh"))
        assertTrue(fastSdStop.contains("[s]rc/app.py -w"))
        assertTrue(fastSdMcpStop.contains("kill -- -\$pgid"))
        assertTrue(fastSdMcpStop.contains("[b]ash start-mcpserver.sh"))
        assertTrue(fastSdMcpStop.contains("[s]rc/app.py --mcp"))
        assertTrue(a1111Stop.contains("kill -- -\$pgid"))
        assertTrue(a1111Stop.contains("[w]ebui.sh"))
        assertTrue(a1111Stop.contains("[s]table-diffusion-webui/launch.py"))
    }

    @Test
    fun `generated stop and status shell blocks keep valid control flow syntax`() {
        val commands = buildList {
            TermuxTools.allTools.forEach { tool ->
                add(TermuxTools.getStopCommand(tool.id))
                add(TermuxTools.getStatusCommand(tool.id))
            }
        }

        commands.forEach { command ->
            assertFalse(command.contains("then;"))
            assertFalse(command.contains("do;"))
            assertFalse(command.contains("else;"))
        }
    }

    @Test
    fun `a1111 run command rewrites visibility before launch`() {
        val localCommand = TermuxTools.getRunCommand("a1111", networkVisible = false)
        val lanCommand = TermuxTools.getRunCommand("a1111", networkVisible = true)

        assertTrue(localCommand.contains("already running"))
        assertTrue(localCommand.contains("find_spec(\\\"clip\\\")"))
        assertTrue(localCommand.contains("export COMMANDLINE_ARGS=\\\"--port 7865 --api"))
        assertTrue(localCommand.contains("ps -o pgid="))
        assertFalse(localCommand.contains("--listen --api"))
        assertTrue(lanCommand.contains("already running"))
        assertTrue(lanCommand.contains("find_spec(\\\"clip\\\")"))
        assertTrue(lanCommand.contains("export COMMANDLINE_ARGS=\\\"--port 7865 --listen --api"))
        assertTrue(lanCommand.contains("ps -o pgid="))
        assertTrue(lanCommand.contains("setsid -f su - auto -c"))
    }

    @Test
    fun `a1111 installer uses python 3_11 mirror repo and preseeded clip`() {
        val commands = TermuxTools.getTool("a1111")!!.installCommands.joinToString("\n")

        assertTrue(commands.contains("conda create -n a1111 python=3.11 -y"))
        assertTrue(commands.contains("joypaul162/Stability-AI-stablediffusion.git"))
        assertTrue(commands.contains("cf1d67a6fd5ea1aa600c4df58e5b47da45f6bdbf"))
        assertTrue(commands.contains("pip install --no-build-isolation https://github.com/openai/CLIP/archive/d50d76daa670286dd6cacf3bcd80b5e4823fc8e1.zip"))
        assertTrue(commands.contains("pip install --no-build-isolation --force-reinstall https://github.com/openai/CLIP/archive/d50d76daa670286dd6cacf3bcd80b5e4823fc8e1.zip"))
        assertTrue(commands.contains("find_spec(\\\"clip\\\")"))
        assertTrue(commands.contains("pip install -r ~/stable-diffusion-webui/requirements_versions.txt"))
        assertTrue(commands.contains("export STABLE_DIFFUSION_REPO"))
        assertTrue(commands.contains("--no-download-sd-model"))
        assertTrue(commands.contains("--skip-prepare-environment"))
        assertTrue(commands.contains("stable-diffusion-webui-assets"))
        assertTrue(commands.contains("generative-models"))
    }
}
