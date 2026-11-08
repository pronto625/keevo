package com.keevo.crm.api;

/** Story 20.13 — a created client and the number of other clients of its store with the same phone. */
public record CreatedClient(ClientView client, int duplicates) {
}
