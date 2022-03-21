package qbittorrent.exporter.handler;

import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qbittorrent.api.ApiClient;
import qbittorrent.api.model.MainData;
import qbittorrent.api.model.Preferences;
import qbittorrent.api.model.ServerState;
import qbittorrent.api.model.Torrent;
import qbittorrent.exporter.collector.QbtCollector;

import java.util.List;

public class QbtHttpHandler implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(QbtHttpHandler.class);
    private static final String CONTENT_TYPE = "text/plain;charset=utf-8";

    private final PrometheusMeterRegistry registry;
    private final QbtCollector collector;
    private final ApiClient client;

    public QbtHttpHandler(final ApiClient client) {
        this.client = client;
        this.registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        this.collector = new QbtCollector();
        this.registry.getPrometheusRegistry().register(collector);
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) {
        LOGGER.info("Beginning prometheus metrics collection...");
        final long start = System.nanoTime();
        try {
            final List<Torrent> torrents = client.getTorrents();
            final Preferences preferences = client.getPreferences();
            final MainData data = client.getMainData();
            final ServerState serverState = data.getServerState();
            collector.clear();
            collector.setAppVersion(client.getVersion());
            collector.setTotalTorrents(torrents.size());
            collector.setGlobalAlltimeDownloadedBytes(serverState.getAlltimeDl());
            collector.setGlobalAlltimeUploadedBytes(serverState.getAlltimeUl());
            collector.setGlobalSessionDownloadedBytes(serverState.getDlInfoData());
            collector.setGlobalSessionUploadedBytes(serverState.getUpInfoData());
            collector.setGlobalDownloadSpeedBytes(serverState.getDlInfoSpeed());
            collector.setGlobalUploadSpeedBytes(serverState.getUpInfoSpeed());
            collector.setGlobalRatio(Double.parseDouble(serverState.getGlobalRatio()));
            collector.setAppDownloadRateLimitBytes(serverState.getDlRateLimit());
            collector.setAppUploadRateLimitBytes(serverState.getUpRateLimit());
            collector.setAppAlternateDownloadRateLimitBytes(preferences.getAltDlLimit());
            collector.setAppAlternateUploadRateLimitBytes(preferences.getAltUpLimit());
            collector.setAppAlternateRateLimitsEnabled(serverState.isUseAltSpeedLimits());
            collector.setAppMaxActiveDownloads(preferences.getMaxActiveDownloads());
            collector.setAppMaxActiveUploads(preferences.getMaxActiveUploads());
            collector.setAppMaxActiveTorrents(preferences.getMaxActiveTorrents());

            for (Torrent torrent : torrents) {
                collector.setTorrentDownloadSpeedBytes(torrent.getName(), torrent.getDownloadSpeed());
                collector.setTorrentUploadSpeedBytes(torrent.getName(), torrent.getUploadSpeed());
                collector.setTorrentTotalDownloadedBytes(torrent.getName(), torrent.getDownloaded());
                collector.setTorrentSessionDownloadedBytes(torrent.getName(), torrent.getDownloadedSession());
                collector.setTorrentTotalUploadedBytes(torrent.getName(), torrent.getTracker(), torrent.getUploaded());
                collector.setTorrentSessionUploadedBytes(torrent.getName(), torrent.getUploadedSession());
                collector.setTorrentEta(torrent.getName(), torrent.getEta());
                collector.setTorrentProgress(torrent.getName(), torrent.getProgress());
                collector.setTorrentTimeActive(torrent.getName(), torrent.getTimeActive());
                collector.setTorrentSeeders(torrent.getName(), torrent.getNumSeeds());
                collector.setTorrentLeechers(torrent.getName(), torrent.getNumLeechs());
                collector.setTorrentRatio(torrent.getName(), torrent.getRatio());
                collector.setTorrentAmountLeftBytes(torrent.getName(), torrent.getAmountLeft());
                collector.setTorrentSizeBytes(torrent.getName(), torrent.getSize());
                // collector.setTorrentInfo(torrent);
            }

            final List<String> states = torrents.stream().map(Torrent::getState).distinct().toList();
            for (String state : states) {
                final long count = torrents.stream().filter(t -> t.getState().equals(state)).count();
                collector.setTorrentStates(state, count);
            }

            final long duration = (System.nanoTime() - start) / 1_000_000;
            LOGGER.info("Completed in {}ms", duration);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, CONTENT_TYPE);
            exchange.getResponseSender().send(registry.scrape());
        } catch (Exception e) {
            LOGGER.error("An error occurred calling API", e);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, CONTENT_TYPE);
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("An error occurred. " + e.getMessage());
        }
    }
}
