#ifndef WINPR_BUILD_FLAGS_H
#define WINPR_BUILD_FLAGS_H

#define WINPR_CFLAGS "-g -DANDROID -fdata-sections -ffunction-sections -funwind-tables -fstack-protector-strong -no-canonical-prefixes -D_FORTIFY_SOURCE=2 -Wformat -Werror=format-security  -fvisibility=hidden -fno-omit-frame-pointer -Wredundant-decls -fsigned-char -Wimplicit-function-declaration -fvisibility=hidden -mfloat-abi=softfp -O3 -DNDEBUG "
#define WINPR_COMPILER_ID "Clang"
#define WINPR_COMPILER_VERSION "17.0.2"
#define WINPR_TARGET_ARCH ""
#define WINPR_BUILD_CONFIG ""
#define WINPR_BUILD_TYPE "Release"

#endif /* WINPR_BUILD_FLAGS_H */
