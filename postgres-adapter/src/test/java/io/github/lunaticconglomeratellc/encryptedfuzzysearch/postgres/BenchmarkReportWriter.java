package io.github.lunaticconglomeratellc.encryptedfuzzysearch.postgres;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Writes aggregate benchmark metadata and results without fixture content or protected tokens. */
final class BenchmarkReportWriter {
  private BenchmarkReportWriter() {}

  static Path write(Map<String, ?> report) throws IOException {
    Path destination =
        Path.of(
            System.getenv()
                .getOrDefault(
                    "EFS_BENCHMARK_REPORT_FILE", "build/reports/benchmark/postgres-load.json"));
    if (!destination.isAbsolute()) {
      destination = Path.of(System.getProperty("user.dir")).resolve(destination);
    }
    Files.createDirectories(destination.toAbsolutePath().getParent());
    Files.writeString(destination, json(report) + System.lineSeparator(), StandardCharsets.UTF_8);
    System.out.println("report_json=" + destination.toAbsolutePath());
    return destination;
  }

  static String sourceRevision() {
    return gitOutput("rev-parse", "HEAD");
  }

  static boolean sourceDirty() {
    String status = gitOutput("status", "--porcelain", "--untracked-files=no");
    return !status.equals("unknown") && !status.isEmpty();
  }

  private static String gitOutput(String... arguments) {
    try {
      String[] command = new String[arguments.length + 1];
      command[0] = "git";
      System.arraycopy(arguments, 0, command, 1, arguments.length);
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) {
        process.destroyForcibly();
        return "unknown";
      }
      return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException exception) {
      return "unknown";
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return "unknown";
    }
  }

  static String json(Map<String, ?> values) {
    StringBuilder output = new StringBuilder();
    appendValue(output, values);
    return output.toString();
  }

  private static void appendValue(StringBuilder output, Object value) {
    if (value == null) {
      output.append("null");
    } else if (value instanceof String text) {
      appendString(output, text);
    } else if (value instanceof Number || value instanceof Boolean) {
      output.append(value);
    } else if (value instanceof Map<?, ?> map) {
      output.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        if (!first) {
          output.append(',');
        }
        first = false;
        appendString(output, String.valueOf(entry.getKey()));
        output.append(':');
        appendValue(output, entry.getValue());
      }
      output.append('}');
    } else if (value instanceof Collection<?> collection) {
      output.append('[');
      boolean first = true;
      for (Object item : collection) {
        if (!first) {
          output.append(',');
        }
        first = false;
        appendValue(output, item);
      }
      output.append(']');
    } else {
      appendString(output, value.toString());
    }
  }

  private static void appendString(StringBuilder output, String value) {
    output.append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> output.append("\\\"");
        case '\\' -> output.append("\\\\");
        case '\b' -> output.append("\\b");
        case '\f' -> output.append("\\f");
        case '\n' -> output.append("\\n");
        case '\r' -> output.append("\\r");
        case '\t' -> output.append("\\t");
        default -> {
          if (character < 0x20) {
            output.append("\\u%04x".formatted((int) character));
          } else {
            output.append(character);
          }
        }
      }
    }
    output.append('"');
  }
}
