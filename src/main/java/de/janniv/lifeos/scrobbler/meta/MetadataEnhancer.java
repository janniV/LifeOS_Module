package de.janniv.lifeos.scrobbler.meta;

import de.janniv.lifeos.scrobbler.NowPlaying;
import de.janniv.lifeos.scrobbler.ScrobblerSettings;
import de.janniv.lifeos.scrobbler.TrackRewriteRule;
import de.janniv.lifeos.scrobbler.util.ArtistNormalizer;
import de.janniv.lifeos.scrobbler.util.ArtistSplitter;
import de.janniv.lifeos.scrobbler.util.PrivacyHttp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Two-stage enricher. First a regex-based rewrite handles "Channel uploaded
 * Artist - Title" patterns; then an optional MusicBrainz lookup verifies the
 * result. Cover art is fetched independently from a chain of providers
 * (iTunes → Deezer → Cover Art Archive) so that even artists missing from
 * MusicBrainz still get artwork. Network calls are rate-limited globally and
 * cached per track key.
 */
public final class MetadataEnhancer {

    private final ScrobblerSettings settings;
    private final MusicBrainzClient mb;
    private final List<CoverProvider> coverProviders;
    private final Path coverCacheDir;
    private final Map<String, NowPlaying> cache = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, NowPlaying> eldest) {
            return size() > 256;
        }
    };

    public MetadataEnhancer(ScrobblerSettings settings, Path coverCacheDir) {
        this.settings = settings;
        this.coverCacheDir = coverCacheDir;
        this.mb = new MusicBrainzClient(settings.socksProxyHost, settings.socksProxyPort, settings.proxyMode);
        PrivacyHttp metaHttp = new PrivacyHttp(settings.socksProxyHost, settings.socksProxyPort, settings.proxyMode);
        // Provider order = lookup order. iTunes first (broadest, fastest), then Deezer
        // for the long tail, then MusicBrainz/CoverArtArchive as last resort.
        this.coverProviders = new ArrayList<>();
        this.coverProviders.add(new ITunesCoverProvider(metaHttp));
        this.coverProviders.add(new DeezerCoverProvider(metaHttp));
    }

    public NowPlaying enhance(NowPlaying input) {
        if (input == null) return null;

        // User-pinned per-track rules win over every heuristic below. When a
        // rule matches, we substitute the overridden fields immediately and
        // skip the title-parser rewrite — the user already told us the truth.
        NowPlaying ruleApplied = applyTrackRewrites(input);
        if (ruleApplied != input) {
            // Still run feat-extraction + normaliser, but skip TitleParser and
            // MusicBrainz: the user's correction should not be second-guessed.
            return finalisePinnedRewrite(ruleApplied);
        }

        NowPlaying parsed = TitleParser.rewrite(input, settings).orElse(input);

        // Pull feature artists out of the song title so they are sent to Maloja as
        // separate collaborators instead of being buried in the title string.
        // E.g. artist="DAHABFLEX", title="Song (feat. ERZIN)" becomes
        //      artist="DAHABFLEX, ERZIN", title="Song".
        List<String> featFromTitle = ArtistSplitter.extractFeatFromTitle(parsed.title());
        if (!featFromTitle.isEmpty()) {
            String cleanedTitle = ArtistSplitter.stripFeatFromTitle(parsed.title());
            String existingArtist = parsed.artist();
            String allArtists = existingArtist.isEmpty()
                ? String.join(", ", featFromTitle)
                : existingArtist + ", " + String.join(", ", featFromTitle);
            parsed = parsed.withEnhanced(allArtists, cleanedTitle, parsed.album());
        }

        String key = parsed.trackKey();
        NowPlaying cached = cache.get(key);
        if (cached != null) return cached;

        NowPlaying corrected = parsed;
        String cachedReleaseId = "";
        if (settings.useMusicBrainz) {
            Optional<MusicBrainzClient.Enrichment> verified = mb.enrich(parsed);
            if (verified.isPresent()) {
                corrected = verified.get().track;
                cachedReleaseId = verified.get().releaseId;
            }
        }

        if (corrected.coverUrl().isBlank()) {
            String cover = lookupCover(corrected.artist(), corrected.title(), corrected.album(), cachedReleaseId);
            if (!cover.isEmpty()) corrected = corrected.withCover(cover);
        }

        // User-defined normalisation runs last so it always wins — aliases and
        // title cleanups are explicit user intent, not heuristics.
        corrected = ArtistNormalizer.normalize(corrected, settings);

        cache.put(key, corrected);
        return corrected;
    }

    /** Applies the first matching user rule to the input. Returns the same
     *  instance unchanged when no rule matches, so callers can detect a hit
     *  via identity check. */
    private NowPlaying applyTrackRewrites(NowPlaying in) {
        if (settings.trackRewrites == null || settings.trackRewrites.isEmpty()) return in;
        for (TrackRewriteRule rule : settings.trackRewrites) {
            if (rule == null) continue;
            if (!rule.matches(in.artist(), in.title())) continue;
            String a = (rule.artist != null && !rule.artist.isBlank()) ? rule.artist : in.artist();
            String t = (rule.title != null && !rule.title.isBlank()) ? rule.title : in.title();
            String al = (rule.album != null && !rule.album.isBlank()) ? rule.album : in.album();
            return in.withEnhanced(a, t, al);
        }
        return in;
    }

    /** Cover lookup + feat extraction + normaliser for a rule-pinned scrobble.
     *  We deliberately do NOT consult MusicBrainz here — the user's pinned
     *  values are authoritative. */
    private NowPlaying finalisePinnedRewrite(NowPlaying pinned) {
        java.util.List<String> featFromTitle = ArtistSplitter.extractFeatFromTitle(pinned.title());
        NowPlaying out = pinned;
        if (!featFromTitle.isEmpty()) {
            String cleanedTitle = ArtistSplitter.stripFeatFromTitle(pinned.title());
            String existingArtist = pinned.artist();
            String allArtists = existingArtist.isEmpty()
                ? String.join(", ", featFromTitle)
                : existingArtist + ", " + String.join(", ", featFromTitle);
            out = out.withEnhanced(allArtists, cleanedTitle, out.album());
        }
        if (out.coverUrl().isBlank()) {
            String cover = lookupCover(out.artist(), out.title(), out.album(), "");
            if (!cover.isEmpty()) out = out.withCover(cover);
        }
        return ArtistNormalizer.normalize(out, settings);
    }

    /**
     * Walks the cover provider chain in order until one returns bytes. Each
     * provider's result is persisted under its own cache key so we don't refetch
     * across restarts. Falls back to MusicBrainz/CoverArtArchive when the
     * release id is known.
     */
    private String lookupCover(String artist, String title, String album, String releaseId) {
        if (coverCacheDir == null) return "";
        try {
            Files.createDirectories(coverCacheDir);
        } catch (Exception ignored) {}

        for (CoverProvider p : coverProviders) {
            try {
                CoverProvider.Result r = p.lookup(artist, title, album);
                if (r == null || r.data == null || r.data.length == 0) continue;
                Path target = coverCacheDir.resolve(p.name() + "_" + r.cacheKey + ".jpg");
                if (!Files.exists(target)) Files.write(target, r.data);
                return target.toUri().toString();
            } catch (Exception ignored) {}
        }
        if (releaseId != null && !releaseId.isBlank()) {
            try {
                Path target = coverCacheDir.resolve("mb_" + releaseId + ".jpg");
                if (!Files.exists(target)) {
                    byte[] data = mb.fetchCoverArt(releaseId);
                    if (data == null || data.length == 0) return "";
                    Files.write(target, data);
                }
                return target.toUri().toString();
            } catch (Exception ignored) {}
        }
        return "";
    }
}
