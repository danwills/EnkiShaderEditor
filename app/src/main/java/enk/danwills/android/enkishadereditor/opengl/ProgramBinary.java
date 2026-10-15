package enk.danwills.android.enkishadereditor.opengl;

import androidx.annotation.NonNull;

// A compiled-and-linked GL program's binary blob, as returned by
// glGetProgramBinary()/accepted by glProgramBinary(). The format int is
// driver-defined and must travel alongside the bytes - it's not portable
// across GL implementations, only meaningful to whichever driver produced
// it (see ProgramBinaryKey.driverSignature()).
public record ProgramBinary(int format, @NonNull byte[] bytes) {
}
