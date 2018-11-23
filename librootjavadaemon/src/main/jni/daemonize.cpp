/* Copyright 2018 Jorrit 'Chainfire' Jongma
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <jni.h>
#include <unistd.h>
#include <stdlib.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <stdio.h>

/* Proper daemonization includes forking, closing the current STDIN/STDOUT/STDERR, creating a new
 * session, and forking again, making sure the twice-forked process becomes a child of init (1) */
static int fork_daemon(int returnParent) {
    pid_t child = fork();
    if (child == 0) {
        close(STDIN_FILENO);
        close(STDOUT_FILENO);
        close(STDERR_FILENO);

        int devNull = open("/dev/null", O_RDWR);
        dup2(devNull, STDIN_FILENO);
        dup2(devNull, STDOUT_FILENO);
        dup2(devNull, STDERR_FILENO);
        close(devNull);

        setsid();
        pid_t child2 = fork();
        if (child2 <= 0) return 0; // success child or error on 2nd fork
        exit(EXIT_SUCCESS);
    }
    if (child < 0) return -1; // error on 1st fork
    int status;
    waitpid(child, &status, 0);
    if (!returnParent) exit(EXIT_SUCCESS);
    return 1; // success parent
}

extern "C" {

int main(int argc, char *argv[], char** envp) {
    if (fork_daemon(0) == 0) {
        execv(argv[1], &argv[1]);
    }
}

}
