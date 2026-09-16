package com.xkondix.multimodal.media;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The gallery index: what was produced, by whom, and what it was asked for.
 *
 * IN REDIS, NOT IN A FIELD — for the same reason chat memory is. The files
 * live on disk and survive a restart; an in-memory index would not, and the
 * user would be left with a folder of UUIDs and no idea which was the stadium
 * and which was the cat.
 *
 * TWO LISTS PER ARTIFACT. One keyed by conversation, one global. The
 * conversation list answers "what did we just make"; the global one answers
 * "what has this lab ever made", which matters because the conversation id is
 * regenerated on every page load — without it, yesterday's work is invisible
 * the moment you refresh.
 *
 * NOT CLEARED BY "Reset memory". That button wipes the model's recollection,
 * which is a different thing from throwing away what it made. Conflating the
 * two is the kind of destructive surprise a demo should not contain.
 *
 * Jackson 3 (tools.jackson) — Boot 4 auto-configures that ObjectMapper, and
 * mixing it with the com.fasterxml one is how you get a bean that serialises
 * with different rules than the rest of the app.
 */
@Slf4j
@Component
public class ArtifactRegistry {

    private static final String KEY_PREFIX = "mm:artifacts:";
    private static final String ALL_KEY = "mm:artifacts:__all__";
    /** A demo, not an archive — keeps a long session from growing unbounded. */
    private static final long MAX_PER_CONVERSATION = 200;
    private static final long MAX_GLOBAL = 500;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final MediaStorage storage;

    public ArtifactRegistry(StringRedisTemplate redis, ObjectMapper mapper, MediaStorage storage) {
        this.redis = redis;
        this.mapper = mapper;
        this.storage = storage;
    }

    public void add(String conversationId, Artifact artifact) {
        try {
            String json = mapper.writeValueAsString(artifact);
            push(KEY_PREFIX + conversationId, json, MAX_PER_CONVERSATION);
            push(ALL_KEY, json, MAX_GLOBAL);
            log.debug("[GALLERY] {} += {} {}", conversationId, artifact.kind(), artifact.url());
        } catch (RuntimeException e) {
            // A gallery entry is a convenience, not the artifact itself — the
            // file is already safely on disk. Never fail the request over it.
            log.warn("[GALLERY] could not record {}: {}", artifact.url(), e.getMessage());
        }
    }

    private void push(String key, String json, long max) {
        redis.opsForList().leftPush(key, json);
        redis.opsForList().trim(key, 0, max - 1);
    }

    /** Just this conversation, newest first. */
    public List<Artifact> list(String conversationId) {
        return read(KEY_PREFIX + conversationId);
    }

    /**
     * Everything this lab has ever produced, newest first.
     *
     * The index wins over the disk scan: an entry the registry knows about
     * keeps its prompt and its real kind (narration vs song — the filesystem
     * cannot tell an mp3 from an mp3). Files with no index entry are appended
     * with a guess from the extension, which is how artifacts from before the
     * registry existed, or from a conversation whose id is long gone, stay
     * reachable instead of rotting in a folder.
     */
    public List<Artifact> listAll() {
        Map<String, Artifact> byUrl = new LinkedHashMap<>();
        read(ALL_KEY).forEach(a -> byUrl.putIfAbsent(a.url(), a));
        storage.scanAll((int) MAX_GLOBAL).forEach(a -> byUrl.putIfAbsent(a.url(), a));

        List<Artifact> out = new ArrayList<>(byUrl.values());
        out.sort(Comparator.comparingLong(Artifact::createdAt).reversed());
        return out;
    }

    private List<Artifact> read(String key) {
        try {
            List<String> raw = redis.opsForList().range(key, 0, -1);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<Artifact> out = new ArrayList<>(raw.size());
            for (String json : raw) {
                try {
                    out.add(mapper.readValue(json, Artifact.class));
                } catch (RuntimeException e) {
                    // One unreadable entry must not blank the whole gallery —
                    // an old record written before a field was added would
                    // otherwise take everything else down with it.
                    log.warn("[GALLERY] skipping unreadable entry: {}", e.getMessage());
                }
            }
            out.sort(Comparator.comparingLong(Artifact::createdAt).reversed());
            return out;
        } catch (RuntimeException e) {
            // Redis down: the conversation still works, the gallery falls
            // back to whatever is on disk.
            log.warn("[GALLERY] could not read {}: {}", key, e.getMessage());
            return List.of();
        }
    }

    /** Clears one conversation's index. Files and the global list stay. */
    public void clear(String conversationId) {
        redis.delete(KEY_PREFIX + conversationId);
        log.info("[GALLERY] cleared index for {} (files kept on disk)", conversationId);
    }
}
