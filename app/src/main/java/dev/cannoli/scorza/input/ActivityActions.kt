package dev.cannoli.scorza.input

interface ActivityActions {
    fun finishAffinity()
    fun restartApp()
    fun startRommPairing(host: String)
    fun startRommCodePairing(host: String, pairCode: String)
}
