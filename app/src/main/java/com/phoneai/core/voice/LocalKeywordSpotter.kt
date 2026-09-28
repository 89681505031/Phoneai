package com.phoneai.core.voice

import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream

class LocalKeywordSpotter(pack: WakeWordPackManager.Pack) {
    private val spotter: KeywordSpotter
    private var stream: OnlineStream

    init {
        val model = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = pack.encoder.absolutePath,
                decoder = pack.decoder.absolutePath,
                joiner = pack.joiner.absolutePath,
            ),
            tokens = pack.tokens.absolutePath,
            numThreads = pack.numThreads,
            provider = "cpu",
            modelType = pack.modelType,
        )
        spotter = KeywordSpotter(
            config = KeywordSpotterConfig(
                modelConfig = model,
                keywordsFile = pack.keywords.absolutePath,
                keywordsScore = pack.keywordsScore,
                keywordsThreshold = pack.keywordsThreshold,
                numTrailingBlanks = pack.numTrailingBlanks,
            )
        )
        stream = spotter.createStream()
    }

    @Synchronized
    fun accept(samples: FloatArray, sampleRate: Int): String? {
        stream.acceptWaveform(samples, sampleRate)
        while (spotter.isReady(stream)) spotter.decode(stream)
        val result = spotter.getResult(stream)
        val keyword = result.keyword.trim()
        if (keyword.isBlank()) return null
        spotter.reset(stream)
        return keyword
    }

    @Synchronized
    fun reset() {
        spotter.reset(stream)
    }

    @Synchronized
    fun release() {
        stream.release()
        spotter.release()
    }
}
