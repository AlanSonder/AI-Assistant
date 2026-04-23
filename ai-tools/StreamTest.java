import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class StreamTest {

    private static final String API_ENDPOINT = "http://localhost:9090/api/v1/translate/stream/text";

    public static void main(String[] args) {
        System.out.println("========================================");
        System.out.println("流式翻译API测试");
        System.out.println("========================================");

        try {
            // 构建请求体
            String requestBody = "{" +
                "\"text\": \"Hello, how are you today? I hope you are doing well.\"," +
                "\"from\": \"en\"," +
                "\"to\": \"zh\"," +
                "\"domain\": \"general\"," +
                "\"style\": \"neutral\"" +
                "}";

            URL url = new URL(API_ENDPOINT);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);

            // 发送请求体
            try (OutputStream os = connection.getOutputStream()) {
                os.write(requestBody.getBytes(StandardCharsets.UTF_8));
            }

            int responseCode = connection.getResponseCode();
            System.out.println("HTTP 状态码: " + responseCode);

            if (responseCode == 200) {
                // 读取 SSE 流
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    System.out.println("\n流式输出结果:");
                    System.out.println("========================================");
                    
                    while ((line = reader.readLine()) != null) {
                        System.out.println("原始行: " + line);
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6);
                            if (!data.isEmpty()) {
                                System.out.println("数据: " + data);
                            }
                        }
                    }
                    
                    System.out.println("========================================");
                    System.out.println("流式输出结束");
                }
            } else {
                // 读取错误信息
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    System.out.println("错误信息:");
                    while ((line = reader.readLine()) != null) {
                        System.out.println(line);
                    }
                }
            }

        } catch (IOException e) {
            System.err.println("测试执行失败: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("\n========================================");
        System.out.println("测试完成");
        System.out.println("========================================");
    }
}
