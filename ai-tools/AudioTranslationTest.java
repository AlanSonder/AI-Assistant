import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.UUID;

public class AudioTranslationTest {

    private static final String AUDIO_FILE_PATH = "C:\\Users\\ADMIN\\Music\\OneRepublic - Apologize.mp3";
    private static final String API_ENDPOINT = "http://localhost:9090/api/v1/translate/audio";
    private static final String SOURCE_LANGUAGE = "en";
    private static final String TARGET_LANGUAGE = "zh";

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("Audio Translation Test Flow");
        System.out.println("========================================");

        // 1. Validate audio file
        System.out.println("1. Validating audio file accessibility and format compatibility...");
        boolean fileValid = validateAudioFile();

        if (!fileValid) {
            System.out.println("   ERROR: Audio file validation failed, test terminated");
            return;
        }

        // 2. Execute audio translation test
        System.out.println("2. Executing audio translation test...");
        testAudioTranslation();

        System.out.println("\n========================================");
        System.out.println("Test flow completed");
        System.out.println("========================================");
    }

    private static boolean validateAudioFile() {
        File audioFile = new File(AUDIO_FILE_PATH);

        // Check if file exists
        if (!audioFile.exists()) {
            System.out.println("   ERROR: Audio file does not exist: " + AUDIO_FILE_PATH);
            return false;
        }

        // Check file format
        String fileName = audioFile.getName();
        String fileExtension = fileName.substring(fileName.lastIndexOf('.')).toLowerCase();
        String[] supportedFormats = {".mp3", ".wav", ".m4a", ".ogg", ".flac"};

        boolean formatSupported = false;
        for (String format : supportedFormats) {
            if (fileExtension.equals(format)) {
                formatSupported = true;
                break;
            }
        }

        if (!formatSupported) {
            System.out.println("   ERROR: Unsupported audio format: " + fileExtension);
            return false;
        }

        // Check file size
        long fileSize = audioFile.length();
        long maxSize = 50 * 1024 * 1024; // 50MB

        if (fileSize > maxSize) {
            System.out.println("   ERROR: Audio file size exceeds limit: " + (fileSize / (1024*1024)) + "MB > 50MB");
            return false;
        }

        System.out.println("   OK: File exists: " + fileName);
        System.out.println("   OK: File format: " + fileExtension);
        System.out.println("   OK: File size: " + (fileSize / (1024*1024.0)) + "MB");
        return true;
    }

    private static void testAudioTranslation() {
        File audioFile = new File(AUDIO_FILE_PATH);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(300))
                .build();

        try {
            long startTime = System.currentTimeMillis();

            // Build multipart/form-data request
            String boundary = "----WebKitFormBoundary" + UUID.randomUUID().toString().substring(0, 16);
            String CRLF = "\r\n";

            // Build request body
            StringBuilder requestBody = new StringBuilder();

            // Add from parameter
            requestBody.append("--").append(boundary).append(CRLF);
            requestBody.append("Content-Disposition: form-data; name=\"from\"").append(CRLF);
            requestBody.append(CRLF);
            requestBody.append(SOURCE_LANGUAGE).append(CRLF);

            // Add to parameter
            requestBody.append("--").append(boundary).append(CRLF);
            requestBody.append("Content-Disposition: form-data; name=\"to\"").append(CRLF);
            requestBody.append(CRLF);
            requestBody.append(TARGET_LANGUAGE).append(CRLF);

            // Add file
            requestBody.append("--").append(boundary).append(CRLF);
            requestBody.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(audioFile.getName()).append("\"")
                    .append(CRLF);
            requestBody.append("Content-Type: audio/mpeg").append(CRLF);
            requestBody.append("Content-Transfer-Encoding: binary").append(CRLF);
            requestBody.append(CRLF);

            // Convert to byte arrays
            byte[] headerBytes = requestBody.toString().getBytes();
            byte[] fileContent = Files.readAllBytes(audioFile.toPath());
            byte[] footerBytes = (CRLF + "--" + boundary + "--" + CRLF).getBytes();

            // Build complete request body
            byte[] completeBody = new byte[headerBytes.length + fileContent.length + footerBytes.length];
            System.arraycopy(headerBytes, 0, completeBody, 0, headerBytes.length);
            System.arraycopy(fileContent, 0, completeBody, headerBytes.length, fileContent.length);
            System.arraycopy(footerBytes, 0, completeBody, headerBytes.length + fileContent.length, footerBytes.length);

            // Create request
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_ENDPOINT))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(completeBody))
                    .build();

            System.out.println("   Calling translation API: " + API_ENDPOINT);
            System.out.println("   Source language: " + SOURCE_LANGUAGE);
            System.out.println("   Target language: " + TARGET_LANGUAGE);
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            long processingTime = System.currentTimeMillis() - startTime;

            // Process response
            System.out.println("   HTTP Status Code: " + response.statusCode());
            System.out.println("   Response Body: " + response.body());
            System.out.println("   Total Processing Time: " + (processingTime / 1000.0) + " seconds");

        } catch (IOException | InterruptedException e) {
            System.err.println("   ERROR: Test execution failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
