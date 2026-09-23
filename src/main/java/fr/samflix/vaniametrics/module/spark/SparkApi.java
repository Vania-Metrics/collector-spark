package fr.samflix.vaniametrics.module.spark;

/**
 * « spark est-il là ? », posé sans nommer aucune plateforme.
 *
 * <p>CETTE CLASSE EXISTE À CAUSE D'UN VRAI PLANTAGE. Le test vivait d'abord sur
 * {@code SparkPaper}, en méthode statique, et l'entrée Velocity l'appelait — ce qui forçait le
 * chargement de {@code SparkPaper}, donc de sa classe mère {@code JavaPlugin}, donc de Bukkit, sur
 * un proxy qui n'en a pas :
 *
 * <pre>
 * Couldn't pass ProxyInitializeEvent to vaniametrics-spark
 * java.lang.NoClassDefFoundError: org/bukkit/plugin/java/JavaPlugin
 * </pre>
 *
 * <p>La règle qu'il faut en retenir, et qui vaut pour tout jar dual-plateforme : <b>une classe
 * commune aux deux entrées ne doit nommer ni Bukkit ni Velocity.</b> La simple référence STATIQUE
 * à une classe suffit à la faire charger, bien avant qu'on en appelle quoi que ce soit.
 */
final class SparkApi {

	private SparkApi() {}

	/**
	 * L'API de spark est-elle chargeable ?
	 *
	 * <p>On ne cherche ni un plugin nommé « spark » ni une instance : Paper INTÈGRE spark au
	 * serveur depuis la 1.21 et il ne figure dans aucune liste de plugins, alors que son API est
	 * bien là. Savoir si l'instance est prête est le travail du collecteur, qui la résout à chaque
	 * relevé.
	 */
	static boolean presente() {
		try {
			Class.forName("me.lucko.spark.api.Spark", false, SparkApi.class.getClassLoader());
			return true;
		} catch (Throwable t) {
			return false;
		}
	}
}
