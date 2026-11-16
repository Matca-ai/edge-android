package ai.matca.edge

/** What the library refuses or cannot do; a failure of any kind leaves the current model working. */
public sealed class EdgeError(message: String) : Exception(message) {
    public class InvalidModelName : EdgeError("the model name is not a DNS label")

    /** `classify` was called before any release reached this device. */
    public class NoModel : EdgeError("no release is installed on this device")

    public class InvalidManifest(reason: String) : EdgeError("the manifest cannot be read: $reason")

    public class Untrusted(reason: String) : EdgeError("the manifest is not trusted: $reason")

    public class Refused(reason: String) : EdgeError("the release was not taken: $reason")

    /** The edge answered with a status the library does not take; 0 when there was no answer. */
    public class Edge(public val status: Int, reason: String) : EdgeError("the edge answered $status: $reason")

    public class FileMismatch(path: String) : EdgeError("the file $path does not match the manifest")

    public class ModelLoadFailed(reason: String) : EdgeError("the model did not load: $reason")

    public class PredictionFailed(reason: String) : EdgeError("the model gave no usable answer: $reason")

    public class ReferenceCheckFailed(path: String) : EdgeError("the reference picture $path was not answered as expected")

    public class UndecodablePicture : EdgeError("the picture cannot be decoded")

    public class UniformPicture : EdgeError("the picture is uniform: illumination has nothing to stretch")

    /** A picture step set to a value it cannot run with. */
    public class InvalidPreprocessing(step: String) : EdgeError("the step $step has a value out of range")

    /** A check stopped on something that is not the library's own failure, such as a full disk. */
    public class UpdateStopped(reason: String) : EdgeError("the update stopped: $reason")
}

/** Only the library's own texts go to a log or an app: another's could quote a request header. */
internal fun describe(error: Throwable): String =
    (if (error is EdgeError) error.message else null) ?: error.javaClass.simpleName
