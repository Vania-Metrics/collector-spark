package fr.samflix.vaniametrics.module.spark;

import org.slf4j.Logger;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;

import fr.samflix.vaniametrics.api.Collector;
import fr.samflix.vaniametrics.api.VaniaMetrics;
import fr.samflix.vaniametrics.api.VaniaMetricsProvider;
import fr.samflix.vaniametrics.api.Version;

/**
 * The spark module, Velocity side — the same collector, a different entry point.
 *
 * <p>Here spark IS a plugin: Velocity doesn't ship it, it has to be installed. The dependency is
 * therefore declared in velocity-plugin.json, and Velocity guarantees load order.
 *
 * <p>Both entry classes live in the same jar. Bukkit reads {@code plugin.yml} and loads
 * {@link SparkPaper}; Velocity reads {@code velocity-plugin.json} and loads this one. Each ignores
 * the other, which is never loaded — that's what allows a single jar per integration.
 */
@Plugin(
		id = "vaniametrics-spark",
		name = "VaniaMetrics Spark",
		version = Version.VALUE,
		description = "Spark metrics for VaniaMetrics.",
		authors = {"mc-vania"},
		dependencies = {
			@com.velocitypowered.api.plugin.Dependency(id = "vaniametrics"),
			@com.velocitypowered.api.plugin.Dependency(id = "spark")
		})
public final class SparkVelocity {

	private final Logger logger;
	private Collector collector;

	@Inject
	public SparkVelocity(Logger logger) {
		this.logger = logger;
	}

	@Subscribe
	public void onInit(ProxyInitializeEvent e) {
		if (!SparkApi.isPresent()) {
			logger.warn("spark not found — module inactive.");
			return;
		}
		VaniaMetrics metrics = VaniaMetricsProvider.get();
		collector = new SparkCollector(metrics.platform());
		metrics.register(collector);
	}

	@Subscribe
	public void onShutdown(ProxyShutdownEvent e) {
		if (collector != null) {
			VaniaMetricsProvider.find().ifPresent(m -> m.unregister(collector));
		}
	}
}
