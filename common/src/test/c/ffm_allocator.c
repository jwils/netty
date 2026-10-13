/*
 * Copyright 2026 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
#include <fcntl.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

/* A separate mapping per allocation makes freeing through libc an ownership error. */
typedef union {
    max_align_t alignment;
    size_t mapped_size;
} allocation_header;

static void record(const char *event) {
    const char *path = getenv("NETTY_FFM_TEST_EVENTS");
    if (path != NULL) {
        int fd = open(path, O_WRONLY | O_APPEND);
        if (fd >= 0) {
            if (write(fd, event, strlen(event)) < 0) {
                abort();
            }
            close(fd);
        }
    }
}

void *malloc(size_t size) {
    if (size == 65535) {
        record("OOM\n");
        return NULL;
    }
    if (size > (size_t) -1 - sizeof(allocation_header)) {
        return NULL;
    }
    size_t mapped_size = size + sizeof(allocation_header);
    allocation_header *header = mmap(NULL, mapped_size, PROT_READ | PROT_WRITE,
                                    MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (header == MAP_FAILED) {
        return NULL;
    }
    header->mapped_size = mapped_size;
    record("malloc\n");
    return header + 1;
}

void free(void *address) {
    if (address != NULL) {
        allocation_header *header = (allocation_header *) address - 1;
        size_t mapped_size = header->mapped_size;
        record("free\n");
        if (munmap(header, mapped_size) != 0) {
            abort();
        }
    }
}
