cmake_minimum_required(VERSION 3.22.1)
project(rdpbridge)

set(FREERDP_IMPORT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

add_library(
    rdpbridge
    SHARED
    native_bridge.cpp
)

include_directories(
    SYSTEM
    ${FREERDP_IMPORT_DIR}/include/freerdp3
    ${FREERDP_IMPORT_DIR}/include/winpr3
    ${FREERDP_IMPORT_DIR}/include
)

add_library(freerdp3-lib SHARED IMPORTED)
set_target_properties(
    freerdp3-lib
    PROPERTIES IMPORTED_LOCATION ${FREERDP_IMPORT_DIR}/libfreerdp3.so
)

add_library(freerdp-client3-lib SHARED IMPORTED)
set_target_properties(
    freerdp-client3-lib
    PROPERTIES IMPORTED_LOCATION ${FREERDP_IMPORT_DIR}/libfreerdp-client3.so
)

add_library(winpr3-lib SHARED IMPORTED)
set_target_properties(
    winpr3-lib
    PROPERTIES IMPORTED_LOCATION ${FREERDP_IMPORT_DIR}/libwinpr3.so
)

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
    rdpbridge
    freerdp3-lib
    freerdp-client3-lib
    winpr3-lib
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
)
