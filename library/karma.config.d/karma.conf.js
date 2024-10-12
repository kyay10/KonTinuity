config.set({
    browserDisconnectTimeout: 600000,
    browserNoActivityTimeout: 600000,
    processKillTimeout: 600000,
    captureTimeout: 600000,
    browserDisconnectTolerance : 3,
    pingTimeout: 600000,
    client: {
        mocha: {
            timeout: 600000
        }
    }
})