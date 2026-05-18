cmake_minimum_required(VERSION 3.21)

project(NuvioQtDesktop VERSION 1.0.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)

get_filename_component(NUVIO_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/.." ABSOLUTE)

if(WIN32)
    set(NUVIO_GRADLEW "${NUVIO_REPO_ROOT}/gradlew.bat")
else()
    set(NUVIO_GRADLEW "${NUVIO_REPO_ROOT}/gradlew")
endif()

set(NUVIO_WEB_OUTPUT "${NUVIO_REPO_ROOT}/composeApp/build/kotlin-webpack/wasmJs/developmentExecutable")
set(NUVIO_WEB_RESOURCES "${NUVIO_REPO_ROOT}/composeApp/build/processedResources/wasmJs/main")
set(NUVIO_WEB_STAGE "${CMAKE_CURRENT_BINARY_DIR}/web")

find_package(Qt6 6.5 REQUIRED COMPONENTS Widgets WebChannel WebEngineCore WebEngineWidgets OpenGL OpenGLWidgets)
find_package(Qt6 6.5 REQUIRED COMPONENTS Quick QuickControls2 QuickWidgets)
find_package(PkgConfig QUIET)

if(PkgConfig_FOUND)
    pkg_check_modules(MPV IMPORTED_TARGET mpv)
endif()

if(MPV_FOUND)
    set(NUVIO_MPV_TARGET PkgConfig::MPV)
else()
    find_path(MPV_INCLUDE_DIR
        NAMES mpv/client.h
        HINTS ENV MPV_ROOT
        PATH_SUFFIXES include
    )
    find_library(MPV_LIBRARY
        NAMES mpv libmpv
        HINTS ENV MPV_ROOT
        PATH_SUFFIXES lib
    )
    if(NOT MPV_INCLUDE_DIR OR NOT MPV_LIBRARY)
        message(FATAL_ERROR "libmpv was not found. Install mpv/libmpv or set MPV_ROOT.")
    endif()

    add_library(nuvio_mpv UNKNOWN IMPORTED)
    set_target_properties(nuvio_mpv PROPERTIES
        IMPORTED_LOCATION "${MPV_LIBRARY}"
        INTERFACE_INCLUDE_DIRECTORIES "${MPV_INCLUDE_DIR}"
    )
    set(NUVIO_MPV_TARGET nuvio_mpv)
endif()

qt_add_executable(NuvioQtDesktop
    MACOSX_BUNDLE
    src/main.cpp
    src/mpv_render_widget.cpp
    src/mpv_render_widget.h
    src/native_player_bridge.cpp
    src/native_player_bridge.h
    src/player_overlay_controller.cpp
    src/player_overlay_controller.h
    src/player_surface_widget.cpp
    src/player_surface_widget.h
)

qt_add_resources(NuvioQtDesktop nuvio_qml_overlay
    PREFIX "/"
    FILES
        qml/PlayerOverlay.qml
        qml/icons/ic_arrow_back.svg
        qml/icons/ic_brightness.svg
        qml/icons/ic_cloud_download.svg
        qml/icons/ic_close.svg
        qml/icons/ic_fast_forward.svg
        qml/icons/ic_fast_rewind.svg
        qml/icons/ic_flag.svg
        qml/icons/ic_forward_10.svg
        qml/icons/ic_lock.svg
        qml/icons/ic_lock_open.svg
        qml/icons/ic_player_aspect_ratio.svg
        qml/icons/ic_player_audio_filled.svg
        qml/icons/ic_player_pause.svg
        qml/icons/ic_player_play.svg
        qml/icons/ic_player_subtitles.svg
        qml/icons/ic_replay_10.svg
        qml/icons/ic_refresh.svg
        qml/icons/ic_skip_next.svg
        qml/icons/ic_speed.svg
        qml/icons/ic_swap_horiz.svg
        qml/icons/ic_video_library.svg
        qml/icons/ic_volume_off.svg
        qml/icons/ic_volume_up.svg
)

target_link_libraries(NuvioQtDesktop
    PRIVATE
        Qt6::Widgets
        Qt6::WebChannel
        Qt6::WebEngineCore
        Qt6::WebEngineWidgets
        Qt6::OpenGL
        Qt6::OpenGLWidgets
        Qt6::Quick
        Qt6::QuickControls2
        Qt6::QuickWidgets
        ${NUVIO_MPV_TARGET}
)

target_compile_definitions(NuvioQtDesktop
    PRIVATE
        NUVIO_WEB_ROOT="${NUVIO_WEB_STAGE}"
)

set_target_properties(NuvioQtDesktop PROPERTIES
    OUTPUT_NAME "Nuvio"
    MACOSX_BUNDLE_BUNDLE_NAME "Nuvio"
    MACOSX_BUNDLE_GUI_IDENTIFIER "com.nuvio.desktop"
)

if(APPLE)
    set(NUVIO_RUNTIME_WEB_DIR "$<TARGET_BUNDLE_CONTENT_DIR:NuvioQtDesktop>/Resources/web")
else()
    set(NUVIO_RUNTIME_WEB_DIR "$<TARGET_FILE_DIR:NuvioQtDesktop>/web")
endif()

add_custom_target(nuvio_web_bundle
    COMMAND "${NUVIO_GRADLEW}" :composeApp:wasmJsBrowserDevelopmentWebpack
    COMMAND "${CMAKE_COMMAND}" -E rm -rf "${NUVIO_WEB_STAGE}"
    COMMAND "${CMAKE_COMMAND}" -E copy_directory "${NUVIO_WEB_RESOURCES}" "${NUVIO_WEB_STAGE}"
    COMMAND "${CMAKE_COMMAND}" -E copy_directory "${NUVIO_WEB_OUTPUT}" "${NUVIO_WEB_STAGE}"
    COMMAND "${CMAKE_COMMAND}" -E rm -rf "${NUVIO_RUNTIME_WEB_DIR}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${NUVIO_RUNTIME_WEB_DIR}"
    COMMAND "${CMAKE_COMMAND}" -E copy_directory "${NUVIO_WEB_STAGE}" "${NUVIO_RUNTIME_WEB_DIR}"
    WORKING_DIRECTORY "${NUVIO_REPO_ROOT}"
    COMMENT "Building and staging the CMP web bundle for Qt6"
    VERBATIM
)

add_dependencies(NuvioQtDesktop nuvio_web_bundle)

add_custom_command(TARGET NuvioQtDesktop POST_BUILD
    COMMAND "${CMAKE_COMMAND}" -E rm -rf "${NUVIO_RUNTIME_WEB_DIR}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${NUVIO_RUNTIME_WEB_DIR}"
    COMMAND "${CMAKE_COMMAND}" -E copy_directory "${NUVIO_WEB_STAGE}" "${NUVIO_RUNTIME_WEB_DIR}"
    COMMENT "Copying CMP web bundle into the Qt6 desktop app"
    VERBATIM
)

install(TARGETS NuvioQtDesktop
    BUNDLE DESTINATION .
    RUNTIME DESTINATION bin
)

if(APPLE)
    find_program(MACDEPLOYQT_EXECUTABLE macdeployqt)
    if(MACDEPLOYQT_EXECUTABLE)
        add_custom_target(NuvioQtDesktopDeploy
            COMMAND "${MACDEPLOYQT_EXECUTABLE}" "$<TARGET_BUNDLE_DIR:NuvioQtDesktop>" -verbose=1
            DEPENDS NuvioQtDesktop
            COMMENT "Deploying Qt frameworks into the macOS app bundle"
            VERBATIM
        )
    endif()
endif()
