cmake_minimum_required(VERSION 3.22.1)
project(home_bridge LANGUAGES C CXX)

# 「系统桌面桥」——libxposed API 102 native 入口（只做最小补丁：读状态 + 转发一个 binder 调用）
# 编译口径：仅 arm64-v8a；libc++ 静态链接（gradle 传 -DANDROID_STL=c++_static）；16KB 页对齐。

add_library(home_bridge SHARED src/home_bridge.cpp)

target_compile_features(home_bridge PRIVATE cxx_std_17)
target_compile_options(home_bridge PRIVATE
    -O2 -Wall -Wextra
    -fvisibility=hidden
    -fno-exceptions -fno-rtti)
target_link_options(home_bridge PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(home_bridge PRIVATE log dl)

# ── 空间壁纸的底图填充（PatchMatch，C 移植）────────────────────────────────
# 纯 C、无第三方依赖；只 arm64-v8a；16KB 页对齐。
add_library(spatial_fill SHARED
    spatial_fill_jni.c
    patchmatch/pm_image.c
    patchmatch/pm_nnf.c
    patchmatch/pm_inpaint.c
    patchmatch/pm_par.c)

target_include_directories(spatial_fill PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(spatial_fill PRIVATE
    -O3 -ffp-contract=fast -funroll-loops
    -Wall -Wextra -Wno-unused-parameter
    -fvisibility=hidden)
target_link_options(spatial_fill PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(spatial_fill PRIVATE log)
