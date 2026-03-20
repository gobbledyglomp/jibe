"""File transfer logic for receiving files from Android.

This module will handle the three-phase file transfer protocol defined
in `docs/protocol.md`:

  1. `file.start` — receive metadata (filename, size, total chunks)
  2. `file.chunk` — receive base64-encoded chunks, decode, and write
     them to a temporary file in order
  3. `file.done` — verify the SHA-256 checksum against the reassembled
     file and move it to the final destination

Design considerations:
  - Chunks are base64-encoded to stay within JSON text frames (binary
    WebSocket frames would be more efficient but harder to debug)
  - The transfer ID (UUID) allows multiple concurrent transfers
  - The checksum ensures data integrity end-to-end

For this milestone, this module is a stub. File transfer will be
implemented in a future milestone.
"""
