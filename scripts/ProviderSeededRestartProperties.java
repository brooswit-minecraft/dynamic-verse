import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Future-wrapper helper: reads a Java .properties key using the same escaping
 * rules as the persisted ManagedVerseStateFileStore data.
 */
final class ProviderSeededRestartProperties {
    private ProviderSeededRestartProperties() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.exit(64);
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(Path.of(args[0]))) {
            properties.load(input);
        }
        String value = properties.getProperty(args[1]);
        if (value == null || value.isEmpty()) {
            System.exit(2);
        }
        System.out.print(value);
    }
}
