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
 * Le module spark, côté Velocity — le même collecteur, un autre point d'entrée.
 *
 * <p>Ici spark EST un plugin : Velocity ne le livre pas, il faut l'installer. La dépendance est
 * donc déclarée dans velocity-plugin.json, et Velocity garantit l'ordre de chargement.
 *
 * <p>Les deux classes d'entrée cohabitent dans le MÊME jar. Bukkit lit {@code plugin.yml} et
 * charge {@link SparkPaper} ; Velocity lit {@code velocity-plugin.json} et charge celle-ci. Chacun
 * ignore l'autre, qui n'est jamais chargée — c'est ce qui permet un seul jar par intégration.
 */
@Plugin(
		id = "vaniametrics-spark",
		name = "VaniaMetrics Spark",
		version = Version.VALEUR,
		description = "Métriques spark pour VaniaMetrics.",
		authors = {"mc-vania"},
		dependencies = {
			@com.velocitypowered.api.plugin.Dependency(id = "vaniametrics"),
			@com.velocitypowered.api.plugin.Dependency(id = "spark")
		})
public final class SparkVelocity {

	private final Logger journal;
	private Collector collecteur;

	@Inject
	public SparkVelocity(Logger journal) {
		this.journal = journal;
	}

	@Subscribe
	public void onInit(ProxyInitializeEvent e) {
		if (!SparkApi.presente()) {
			journal.warn("spark introuvable — module inactif.");
			return;
		}
		VaniaMetrics metriques = VaniaMetricsProvider.get();
		collecteur = new SparkCollector(metriques.plateforme());
		metriques.enregistrer(collecteur);
	}

	@Subscribe
	public void onShutdown(ProxyShutdownEvent e) {
		if (collecteur != null) {
			VaniaMetricsProvider.chercher().ifPresent(m -> m.retirer(collecteur));
		}
	}
}
