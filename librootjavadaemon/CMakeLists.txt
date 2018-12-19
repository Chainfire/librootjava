cmake_minimum_required(VERSION 3.4.1)


add_executable( libdaemonize.so src/main/jni/daemonize.cpp )

target_link_libraries( libdaemonize.so log )

#target_compile_definitions( libdaemonize.so PRIVATE DEBUG )
