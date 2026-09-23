package fr.samflix.vaniametrics.module.spark;

import net.md_5.bungee.api.plugin.Plugin;

import fr.samflix.vaniametrics.api.Collector;
import fr.samflix.vaniametrics.api.VaniaMetrics;
import fr.samflix.vaniametrics.api.VaniaMetricsProvider;

/**
 * The spark module, BungeeCord and Waterfall side — the same collector, a third entry point.
 *
 * <p>As on Velocity, spark is a plugin here and has to be installed; {@code depends} in bungee.yml
 * makes BungeeCord enable it, and the core, first. BungeeCord reads {@code bungee.yml} before
 * {@code plugin.yml}, which is Bukkit's, and loads this class; Velocity reads
 * {@code velocity-plugin.json}. One jar per integration still.
 */
public final class SparkBungee extends Plugin {

	private Collector collector;

	@Override
	public void onEnable() {
		if (!SparkApi.isPresent()) {
			getLogger().warning("spark not found — module inactive.");
			return;
		}
		VaniaMetrics metrics = VaniaMetricsProvider.get();
		collector = new SparkCollector(metrics.platform());
		metrics.register(collector);
	}

	@Override
	public void onDisable() {
		if (collector != null) {
			VaniaMetricsProvider.find().ifPresent(m -> m.unregister(collector));
		}
	}
}
