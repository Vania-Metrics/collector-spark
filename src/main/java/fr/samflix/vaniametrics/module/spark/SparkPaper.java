package fr.samflix.vaniametrics.module.spark;

import org.bukkit.plugin.java.JavaPlugin;

import fr.samflix.vaniametrics.api.Collector;
import fr.samflix.vaniametrics.api.VaniaMetrics;
import fr.samflix.vaniametrics.api.VaniaMetricsProvider;

/**
 * Le module spark, côté Paper.
 *
 * <p>SPARK NE SE DÉCLARE PAS EN {@code depend} DANS LE plugin.yml, et c'est la particularité de ce
 * module : depuis la 1.21, Paper INTÈGRE spark au serveur au lieu de le charger comme plugin. Il
 * ne figure dans aucune liste — {@code /plugins} ne le montre pas — alors que ses commandes
 * répondent et que son API est chargée. Un {@code depend: [spark]} empêcherait donc ce module de
 * démarrer précisément là où spark est présent d'office.
 *
 * <p>On vérifie à la place que l'API est CHARGEABLE — voir {@link SparkApi}, qui porte ce test
 * précisément pour qu'il soit appelable des deux côtés sans traîner Bukkit avec lui.
 */
public final class SparkPaper extends JavaPlugin {

	private Collector collecteur;

	@Override
	public void onEnable() {
		if (!SparkApi.presente()) {
			getLogger().warning("spark introuvable sur ce serveur — module inactif.");
			return;
		}
		VaniaMetrics metriques = VaniaMetricsProvider.get();
		collecteur = new SparkCollector(metriques.plateforme());
		metriques.enregistrer(collecteur);
	}

	@Override
	public void onDisable() {
		if (collecteur != null) {
			VaniaMetricsProvider.chercher().ifPresent(m -> m.retirer(collecteur));
		}
	}
}
