package com.example.buddyug

/**
 * Contenu des jeux à marqueurs du stand (cartes imprimées : cards/cartes-buddy.pdf,
 * dictionnaire ArUco DICT_4X4_50).
 *
 * Tout ce qui concerne Epitech ici reprend les infos vérifiées du prompt
 * (pages epitech.eu, 2026-11-11) : ne rien ajouter qui n'y figure pas.
 */
object Cards {
    const val A = 0
    const val B = 1
    const val C = 2
    const val D = 3
    const val VRAI = 10
    const val FAUX = 11
    const val PLAY_PROFILE = 20
    const val PLAY_QUIZ = 21

    val LETTERS = listOf(A, B, C, D)
    val ANSWERS = setOf(A, B, C, D, VRAI, FAUX)
    fun letter(id: Int) = "ABCD"[LETTERS.indexOf(id)].toString()
}

// --- Jeu 1 : « Profil tech » ---

enum class Profile(val title: String, val text: String) {
    CYBER(
        "Cybersécurité",
        "Tu aimes comprendre les systèmes pour mieux les protéger. Chez Epitech, ça mène au Bachelor Cybersécurité, à la majeure Cybersécurité du Programme Grande École, ou au MSc Cybersécurité."
    ),
    IA(
        "IA et Data",
        "Tu veux faire parler les données et les machines. Chez Epitech, ça mène au Bachelor Intelligence Artificielle, à la majeure Intelligence Artificielle du Programme Grande École, ou aux MSc Intelligence Artificielle et Big Data."
    ),
    DEV(
        "Dev et Création",
        "Tu aimes créer des applis et des mondes que les autres utilisent. Chez Epitech, ça mène au Bachelor Concepteur Développeur d'applications, à la majeure Ingénierie logicielle du Programme Grande École, ou au MSc Systèmes Immersifs et Réalités virtuelles."
    ),
    ROBOT(
        "Robotique et IoT",
        "Tu veux que le code bouge dans le monde réel. Chez Epitech, ça mène au MSc Robotique et IoT, et au Hub du campus de Saint-André on trouve des voitures autonomes Robocar et des bras robotiques LeRobot."
    ),
}

/** Une question : 4 réponses, dans l'ordre des cartes A, B, C, D. */
data class ProfileQuestion(val text: String, val options: List<Pair<String, Profile>>)

val PROFILE_QUESTIONS = listOf(
    ProfileQuestion(
        "Le week-end, tu préfères…",
        listOf(
            "démonter un objet pour voir comment il marche" to Profile.ROBOT,
            "créer un jeu ou une appli" to Profile.DEV,
            "résoudre une énigme ou un escape game" to Profile.CYBER,
            "comprendre comment une appli devine ce que tu aimes" to Profile.IA,
        )
    ),
    ProfileQuestion(
        "Dans un film de science-fiction, tu serais…",
        listOf(
            "le hacker qui déjoue les attaques" to Profile.CYBER,
            "l'intelligence artificielle qui aide l'équipage" to Profile.IA,
            "l'ingénieur qui construit les robots" to Profile.ROBOT,
            "le créateur du monde virtuel" to Profile.DEV,
        )
    ),
    ProfileQuestion(
        "Ce qui te rendrait le plus fier…",
        listOf(
            "un objet que tu as fabriqué et programmé" to Profile.ROBOT,
            "un site ou un jeu que plein de gens utilisent" to Profile.DEV,
            "une prédiction qui tombe juste" to Profile.IA,
            "un piège que personne n'arrive à contourner" to Profile.CYBER,
        )
    ),
    ProfileQuestion(
        "Ton super-pouvoir, ce serait…",
        listOf(
            "voir les failles que les autres ne voient pas" to Profile.CYBER,
            "parler aux machines" to Profile.ROBOT,
            "trouver la logique dans des milliers de chiffres" to Profile.IA,
            "imaginer des interfaces que tout le monde adore" to Profile.DEV,
        )
    ),
)

/** Profil le plus choisi ; en cas d'égalité, celui choisi en premier. */
fun profileResult(answers: List<Profile>): Profile {
    val counts = answers.groupingBy { it }.eachCount()
    val best = counts.values.max()
    return answers.first { counts[it] == best }
}

// --- Jeu 2 : « Quiz Vrai ou Faux » ---

data class QuizItem(val statement: String, val answer: Boolean, val explanation: String)

const val QUIZ_LENGTH = 5

val QUIZ_ITEMS = listOf(
    QuizItem("À Epitech, il faut déjà savoir coder pour entrer.", false,
        "Aucun prérequis en programmation : la motivation compte plus que le niveau de départ."),
    QuizItem("Le Programme Grande École se rejoint via Parcoursup.", true,
        "En un seul vœu, avec un concours où l'entretien de motivation compte le plus."),
    QuizItem("Le Bachelor Epitech passe aussi par Parcoursup.", false,
        "Le Bachelor est hors Parcoursup : on dépose sa candidature et on passe un entretien."),
    QuizItem("À Epitech, il y a des cours magistraux en amphi tous les jours.", false,
        "La pédagogie est par projets, sans cours magistraux."),
    QuizItem("Au Programme Grande École, on part à l'international en 4e année.", true,
        "Une année complète à l'étranger, ou un stage plus un semestre à l'étranger."),
    QuizItem("Le Hub du campus de Saint-André a des imprimantes 3D.", true,
        "Il en a cinq, en plus des bras robotiques et des voitures autonomes."),
    QuizItem("Les MSc Epitech se font en alternance.", true,
        "Un jour par semaine à l'école, le reste en entreprise."),
    QuizItem("La Piscine d'Epitech, c'est un cours de natation.", false,
        "C'est un bootcamp intensif d'immersion totale dans le code."),
    QuizItem("Un octet, c'est 8 bits.", true,
        "Et avec 8 bits, on peut écrire 256 valeurs différentes."),
)
