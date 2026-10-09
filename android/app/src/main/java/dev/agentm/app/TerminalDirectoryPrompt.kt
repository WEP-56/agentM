package dev.agentm.app

/** Bash OSC 7 URI: quote expansions and escape URI separators in literal project names. */
object TerminalDirectoryPrompt {
    const val COMMAND = "__agentm_directory=\"\${PWD//%/%25}\"; " +
        "__agentm_directory=\"\${__agentm_directory//#/%23}\"; " +
        "__agentm_directory=\"\${__agentm_directory//\\?/%3F}\"; " +
        "__agentm_directory=\"\${__agentm_directory// /%20}\"; " +
        "printf '\\033]7;file://localhost%s\\007' \"\$__agentm_directory\""
}
