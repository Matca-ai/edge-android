package ai.matca.edge

import com.google.ai.edge.litert.CompiledModel
import java.io.File

/** Maps a release's `.tflite` with LiteRT on the CPU. */
internal class LiteRtModelLoader : ModelLoader {
    override fun load(modelFile: File): ModelRunner = LiteRtModelRunner(CompiledModel.create(modelFile.absolutePath))
}

/** One compiled model with its buffers; one picture at a time, which the classifier guarantees. */
internal class LiteRtModelRunner(private val model: CompiledModel) : ModelRunner {
    private val inputs = model.createInputBuffers()
    private val outputs = model.createOutputBuffers()

    override fun run(input: FloatArray): FloatArray {
        inputs[0].writeFloat(input)
        model.run(inputs, outputs)
        return outputs[0].readFloat()
    }

    override fun close() {
        inputs.forEach { it.close() }
        outputs.forEach { it.close() }
        model.close()
    }
}
