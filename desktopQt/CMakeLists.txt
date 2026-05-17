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

find_package(Qt6 6.5 REQUIRED COMPONENTS Widgets WebEngineCore WebEngineWidgets)

qt_add_executable(NuvioQtDesktop
    MACOSX_BUNDLE
    src/main.cpp
)

target_link_libraries(NuvioQtDesktop
    PRIVATE
        Qt6::Widgets
        Qt6::WebEngineCore
        Qt6::WebEngineWidgets
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
