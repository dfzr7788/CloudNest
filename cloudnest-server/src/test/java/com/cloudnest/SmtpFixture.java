package com.cloudnest;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Loopback SMTP protocol fixture; no external mail delivery. */
final class SmtpFixture implements AutoCloseable {
  final ServerSocket socket = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
  final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
  volatile boolean stopped;
  final Thread worker;

  SmtpFixture() throws IOException {
    worker =
        Thread.ofPlatform()
            .daemon()
            .start(
                () -> {
                  while (!stopped)
                    try (var client = socket.accept()) {
                      // JavaMail may resolve the host name before its first EHLO on Windows.
                      client.setSoTimeout(30000);
                      var reader =
                          new BufferedReader(
                              new InputStreamReader(
                                  client.getInputStream(), StandardCharsets.UTF_8));
                      var writer =
                          new PrintWriter(
                              new OutputStreamWriter(
                                  client.getOutputStream(), StandardCharsets.UTF_8),
                              true);
                      writer.print("220 localhost ESMTP\r\n");
                      writer.flush();
                      String line;
                      while ((line = reader.readLine()) != null) {
                        if (line.startsWith("DATA")) {
                          writer.print("354 Send message\r\n");
                          writer.flush();
                          var body = new StringBuilder();
                          while ((line = reader.readLine()) != null && !line.equals("."))
                            body.append(line).append("\r\n");
                          var message =
                              new jakarta.mail.internet.MimeMessage(
                                  jakarta.mail.Session.getInstance(new Properties()),
                                  new ByteArrayInputStream(
                                      body.toString().getBytes(StandardCharsets.UTF_8)));
                          messages.add(message.getContent().toString());
                          writer.print("250 Stored\r\n");
                        } else if (line.startsWith("QUIT")) {
                          writer.print("221 Bye\r\n");
                          writer.flush();
                          break;
                        } else writer.print("250 OK\r\n");
                        writer.flush();
                      }
                    } catch (Exception error) {
                      if (!stopped) {
                        error.printStackTrace();
                        messages.offer("SMTP_FIXTURE_ERROR");
                      }
                    }
                });
  }

  String token(String parameter) throws Exception {
    String body = messages.poll(30, TimeUnit.SECONDS);
    if (body == null) throw new AssertionError("No SMTP message received");
    var matcher = java.util.regex.Pattern.compile(parameter + "=([A-Za-z0-9_-]{43})").matcher(body);
    if (!matcher.find()) throw new AssertionError("Missing recovery token in SMTP message");
    return matcher.group(1);
  }

  public void close() throws IOException {
    stopped = true;
    socket.close();
  }
}
