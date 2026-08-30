package com.example.homedocsagent;

/**
 * Home-side agent. Runs on the home Windows PC (which is not always on).
 *
 * <p>Responsibility: dial OUT to the VPS over HTTPS (pull model), download queued digitized
 * documents, write each to a local folder on the PC, verify, acknowledge — after which the VPS
 * deletes its copy. No inbound access to the home network is required. (The local folder is
 * backed up out-of-band to a removable SSD.)
 *
 * <p>Skeleton only. The pull -> write -> verify -> ack loop is implemented in step 5 of
 * {@code docs/PLAN.md}. Kept minimal on purpose (no Spring) so the footprint on the home PC stays small.
 */
public final class HomeDocsAgent {

    private HomeDocsAgent() {
    }

    public static void main(String[] args) {
        System.out.println("home-docs-agent: skeleton - pull loop not implemented yet (see docs/PLAN.md, step 5).");
    }
}
