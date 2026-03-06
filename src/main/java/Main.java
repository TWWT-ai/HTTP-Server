import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


public class Main {
  public static void main(String[] args) {
    System.out.println("Logs from your program will appear here!");
    Path directory = getDirectory(args);

    try (ServerSocket serverSocket = new ServerSocket(4221)) {
      serverSocket.setReuseAddress(true);

      while (true) {
        Socket clientSocket = serverSocket.accept();
        Thread clientThread = new Thread(() -> handleClient(clientSocket, directory));
        clientThread.start();
      }
    } catch (IOException e) {
      System.out.println("IOException: " + e.getMessage());
    }
  }

  private static Path getDirectory(String[] args) {
    for (int i = 0; i < args.length - 1; i++) {
      if ("--directory".equals(args[i])) {
        return Paths.get(args[i + 1]);
      }
    }
    return null;
  }

  private static void handleClient(Socket clientSocket, Path directory) {
    try (Socket socket = clientSocket;
         OutputStream outputStream = socket.getOutputStream();
         InputStream inputStream = socket.getInputStream();
         BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

      String requestLine = reader.readLine();
      if (requestLine == null || requestLine.isEmpty()) {
        return;
      }

      String[] parts = requestLine.split(" ");
      if (parts.length < 2) {
        outputStream.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        return;
      }

      String method = parts[0];
      String path = parts[1];
      String userAgent = "";
      int contentLength = 0;
      boolean hasContentLength = false;
      String line;

      while ((line = reader.readLine()) != null && !line.isEmpty()) {
        int colonIndex = line.indexOf(':');
        if (colonIndex == -1) {
          continue;
        }

        String headerName = line.substring(0, colonIndex).trim().toLowerCase();
        String headerValue = line.substring(colonIndex + 1).trim();

        if ("user-agent".equals(headerName)) {
          userAgent = headerValue;
        } else if ("content-length".equals(headerName)) {
          try {
            contentLength = Integer.parseInt(headerValue);
            hasContentLength = true;
          } catch (NumberFormatException e) {
            outputStream.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            return;
          }
        }
      }

      if ("POST".equals(method) && path.startsWith("/files/")) {
        if (directory == null) {
          outputStream.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes(StandardCharsets.UTF_8));
          return;
        }

        if (!hasContentLength || contentLength < 0) {
          outputStream.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes(StandardCharsets.UTF_8));
          return;
        }

        String filename = path.substring("/files/".length());
        Path requestedPath = directory.resolve(filename).normalize();
        Path normalizedDirectory = directory.normalize();

        if (!requestedPath.startsWith(normalizedDirectory)) {
          outputStream.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes(StandardCharsets.UTF_8));
          return;
        }

        String requestBody = readRequestBody(reader, contentLength);
        Files.write(requestedPath, requestBody.getBytes(StandardCharsets.UTF_8));
        outputStream.write("HTTP/1.1 201 Created\r\n\r\n".getBytes(StandardCharsets.UTF_8));
      } else if (path.equals("/")) {
        outputStream.write("HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.UTF_8));
      } else if (path.startsWith("/echo/")) {
        String echoString = path.substring(6);
        byte[] body = echoString.getBytes(StandardCharsets.UTF_8);
        String responseHeaders =
            "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: " + body.length + "\r\n\r\n";
        outputStream.write(responseHeaders.getBytes(StandardCharsets.UTF_8));
        outputStream.write(body);
      } else if (path.startsWith("/user-agent")) {
        byte[] body = userAgent.getBytes(StandardCharsets.UTF_8);
        String responseHeaders =
            "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: " + body.length + "\r\n\r\n";
        outputStream.write(responseHeaders.getBytes(StandardCharsets.UTF_8));
        outputStream.write(body);
      } else if ("GET".equals(method) && path.startsWith("/files/")) {
        if (directory == null) {
          outputStream.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes(StandardCharsets.UTF_8));
          return;
        }

        String filename = path.substring("/files/".length());
        Path requestedPath = directory.resolve(filename).normalize();
        Path normalizedDirectory = directory.normalize();

        if (!requestedPath.startsWith(normalizedDirectory)
            || !Files.exists(requestedPath)
            || !Files.isRegularFile(requestedPath)) {
          outputStream.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes(StandardCharsets.UTF_8));
          return;
        }

        byte[] fileContents = Files.readAllBytes(requestedPath);
        String responseHeaders = "HTTP/1.1 200 OK\r\n"
            + "Content-Type: application/octet-stream\r\n"
            + "Content-Length: " + fileContents.length + "\r\n\r\n";
        outputStream.write(responseHeaders.getBytes(StandardCharsets.UTF_8));
        outputStream.write(fileContents);
      } else {
        outputStream.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes(StandardCharsets.UTF_8));
      }
    } catch (IOException e) {
      System.out.println("IOException: " + e.getMessage());
    }
  }

  private static String readRequestBody(BufferedReader reader, int contentLength) throws IOException {
    char[] bodyChars = new char[contentLength];
    int totalRead = 0;

    while (totalRead < contentLength){
      int read = reader.read(bodyChars, totalRead, contentLength - totalRead);
      if (read == -1) {
        break;
      }
      totalRead += read;
    }
    return new String(bodyChars, 0, totalRead);
  }
}
