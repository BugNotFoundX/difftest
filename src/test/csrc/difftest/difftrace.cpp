#include <sys/stat.h>
#include <sys/types.h>

#include "difftrace.h"
#include <stdexcept>
#include <string>

DiffTrace::DiffTrace(const char *_trace_name, bool is_read, uint64_t _buffer_size)
  : is_read(is_read) {
  if (!is_read) {
    buffer_size = _buffer_size;
    buffer = (DiffTestState *)calloc(buffer_size, sizeof(DiffTestState));
  }
  if (strlen(_trace_name) >= sizeof(trace_name))
    throw std::runtime_error("DiffTrace name must be shorter than 32 bytes");
  strcpy(trace_name, _trace_name);
}

bool DiffTrace::append(const DiffTestState *trace) {
  memcpy(buffer + buffer_count, trace, sizeof(DiffTestState));
  buffer_count++;
  if (buffer_count == buffer_size) {
    return trace_file_next();
  }
  return 0;
}

bool DiffTrace::read_next(DiffTestState *trace) {
  if (!buffer || buffer_count == buffer_size) {
    trace_file_next();
  }
  memcpy(trace, buffer + buffer_count, sizeof(DiffTestState));
  buffer_count++;
  // printf("%lu...\n", buffer_count);
  return 0;
}


bool DiffTrace::trace_file_next() {
  char filename[128];
  char *noop_home = getenv("NOOP_HOME");
  if (!noop_home) throw std::runtime_error("DiffTrace requires NOOP_HOME");
  snprintf(filename, 128, "%s/%s", noop_home, trace_name);
  mkdir(filename, 0755);
  const char *prefix = "bin";
  snprintf(filename, 128, "%s/%s/%lu.%s", noop_home, trace_name, trace_index, prefix);
  if (is_read) {
    FILE *file = fopen(filename, "rb");
    if (!file) {
      throw std::runtime_error(std::string("DiffTrace missing file: ") + filename);
    }
    // check the number of traces
    fseek(file, 0, SEEK_END);
    long bytes = ftell(file);
    size_t header_size = 0;
#ifdef DIFFTEST_SCHEMA_FINGERPRINT
    const uint64_t expected[] = {0x44544631, DIFFTEST_SCHEMA_VERSION, DIFFTEST_SCHEMA_FINGERPRINT, sizeof(DiffTestState)};
    uint64_t header[4];
    rewind(file);
    if (fread(header, sizeof(header), 1, file) != 1 || memcmp(header, expected, sizeof(header))) {
      fclose(file);
      throw std::runtime_error("DiffTrace target/schema mismatch");
    }
    header_size = sizeof(header);
#endif
    if (bytes <= long(header_size) || (bytes - header_size) % sizeof(DiffTestState)) {
      fclose(file);
      throw std::runtime_error("Empty or truncated DiffTrace");
    }
    buffer_size = (bytes - header_size) / sizeof(DiffTestState);
    if (buffer) {
      free(buffer);
    }
    buffer = (DiffTestState *)calloc(buffer_size, sizeof(DiffTestState));
    // read the binary file
    Info("Loading %lu traces from %s ...\n", buffer_size, filename);
    fseek(file, header_size, SEEK_SET);
    uint64_t read_bytes = fread(buffer, sizeof(DiffTestState), buffer_size, file);
    assert(read_bytes == buffer_size);
    fclose(file);
    buffer_count = 0;
  }
  else if (buffer_count > 0) {
    Info("Writing %lu traces to %s ...\n", buffer_count, filename);
    FILE *file = fopen(filename, "wb");
    if (!file) { perror("DiffTrace open"); exit(1); }
#ifdef DIFFTEST_SCHEMA_FINGERPRINT
    const uint64_t header[] = {0x44544631, DIFFTEST_SCHEMA_VERSION, DIFFTEST_SCHEMA_FINGERPRINT, sizeof(DiffTestState)};
    if (fwrite(header, sizeof(header), 1, file) != 1) { perror("DiffTrace header"); exit(1); }
#endif
    if (fwrite(buffer, sizeof(DiffTestState), buffer_count, file) != buffer_count) {
      perror("DiffTrace write"); exit(1);
    }
    fclose(file);
    buffer_count = 0;
  }
  trace_index++;
  return 0;
}
