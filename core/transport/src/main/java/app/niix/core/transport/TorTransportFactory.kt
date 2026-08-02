package app.niix.core.transport

import kotlinx.coroutines.CoroutineScope

object TorTransportFactory {

    fun create(
        scope: CoroutineScope,
        onionKeyStore: OnionKeyStore = InMemoryOnionKeyStore(),
        provider: TorProcessProvider = ExternalTorProcessProvider(),
        bridgeConfig: BridgeConfig = BridgeConfig(),
    ): TorTransport = RealTorTransport(provider, scope, onionKeyStore, bridgeConfig)

    fun placeholder(): TorTransport = PlaceholderTorTransport()
}
