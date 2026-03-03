package com.keevo.sync.sync.adapter.in.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SyncController — REST adapter for sync operations.
 *
 * <p>Both endpoints return 501 Not Implemented until Story 5.1/5.2.
 * The MCP placeholder for this controller is in adapter/in/mcp/.gitkeep.
 */
@RestController
@RequestMapping("/api/v1/sync")
public class SyncController {

    /**
     * POST /api/v1/sync/push
     * Push local operations to the backend for processing.
     *
     * <p>STUB — Returns 501 until Story 5.1.
     */
    @PostMapping("/push")
    public ResponseEntity<Void> push(@RequestBody Object payload) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }

    /**
     * GET /api/v1/sync/pull
     * Pull delta changes since the given timestamp.
     *
     * <p>STUB — Returns 501 until Story 5.2.
     *
     * @param since ISO-8601 timestamp (optional; omit for full sync)
     */
    @GetMapping("/pull")
    public ResponseEntity<Void> pull(
            @RequestParam(required = false) String since) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
