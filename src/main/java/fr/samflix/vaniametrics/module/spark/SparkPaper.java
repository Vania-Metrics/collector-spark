package fr.samflix.vaniametrics.module.spark;

import org.bukkit.plugin.java.JavaPlugin;

import fr.samflix.vaniametrics.api.Collector;
import fr.samflix.vaniametrics.api.VaniaMetrics;
import fr.samflix.vaniametrics.api.VaniaMetricsProvider;

/**
 * The spark module, Paper side.
 *
 * <p>Spark is deliberately NOT declared in {@code depend} in plugin.yml: since 1.21, Paper embeds
 * spark in the server instead of loading it as a plugin. It doesn't appear in any list —
 * {@code /plugins} won't show it — even though its commands respond and its API is loaded. A
 * {@code depend: [spark]} would keep this module from starting precisely where spark is present
 * by default.
 *
 * <p>Instead we check that the API is loadable — see {@link SparkApi}, which carries that check
 * specifically so it can be called from either side without dragging Bukkit along.
 */
public final class SparkPaper extends JavaPlugin {

	private Collector collector;

	@Override
	public void onEnable() {
		if (!SparkApi.isPresent()) {
			getLogger().warning("spark not found on this server — module inactive.");
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
