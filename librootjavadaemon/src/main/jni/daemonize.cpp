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
#include <errno.h>
#include <time.h>

#ifdef DEBUG
#include <android/log.h>
#define LOG(...) ((void)__android_log_print(ANDROID_LOG_DEBUG, "libdaemonize", __VA_ARGS__))
#else
#define LOG(...) ((void)0)
#endif

int sleep_ms(int ms) {
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (ms % 1000) * 1000000;
    if ((nanosleep(&ts,&ts) == -1) && (errno == EINTR)) {
        int ret = (ts.tv_sec * 1000) + (ts.tv_nsec / 1000000);
        if (ret < 1) ret = 1;
        return ret;
    }
    return 0;
}

/* Proper daemonization includes forking, closing the current STDIN/STDOUT/STDERR, creating a new
 * session, and forking again, making sure the twice-forked process becomes a child of init (1) */
static int fork_daemon(int returnParent) {
    pid_t child = fork();
    if (child == 0) { // 1st child
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
        if (child2 == 0) { // 2nd child
            return 0; // return execution to caller
        } else if (child2 > 0) { // 1st child, fork ok
            exit(EXIT_SUCCESS);
        } else if (child2 < 0) { // 1st child, fork fail
            LOG("2nd fork failed (%d)", errno);
            exit(EXIT_FAILURE);
        }
    }

    // parent
    if (child < 0) {
        LOG("1st fork failed (%d)", errno);
        return -1; // error on 1st fork
    }
    while (true) {
        int status;
        pid_t waited = waitpid(child, &status, 0);
        if ((waited == child) && WIFEXITED(status)) {
            break;
        }
    }
    if (!returnParent) exit(EXIT_SUCCESS);
    return 1; // success parent
}

extern "C" {

int main(int argc, char *argv[], char** envp) {
    if (fork_daemon(0) == 0) { // daemonized child
        // On some devices in the early boot stages, execv will fail with EACCESS, cause unknown.
        // Retrying a couple of times seems to work. Most-seen required attempts is three.
        // That retrying works implies some sort of race-condition, possibly SELinux related.
        for (int i = 0; i < 16; i++) {
            execv(argv[1], &argv[1]); // never returns if successful
            LOG("[%d] execv(%s, ...)-->%d", i, argv[1], errno);
            sleep_ms(16);
        }
        LOG("too many failures, aborting");
        exit(EXIT_FAILURE);
    }
}

}
