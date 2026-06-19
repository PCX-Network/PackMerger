package sh.pcx.packmerger.bedrock;

/**
 * Options for a {@link BedrockConverter} run.
 *
 * @param packName the human-readable name embedded in the Bedrock pack manifest;
 *                 also seeds the deterministic manifest UUIDs
 * @param debug    when {@code true}, the converter records a warning for every
 *                 item/model it skips (useful for diagnosing coverage gaps)
 */
public record BedrockConverterOptions(String packName, boolean debug) {

    public BedrockConverterOptions {
        if (packName == null || packName.isBlank()) packName = "PackMerger";
    }
}
