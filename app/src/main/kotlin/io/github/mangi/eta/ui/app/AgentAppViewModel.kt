package io.github.mangi.eta.ui.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel

/** Activity borrows process-owned Agent and general terminal state. */
internal class AgentAppViewModel(application: Application) : AndroidViewModel(application) {
    val state = AgentSessionHost.get(application).state
    private val terminalHost = TerminalSessionHost.get(application)
    val terminalStore = terminalHost.terminal
    val consoleStore = terminalHost.console
}
