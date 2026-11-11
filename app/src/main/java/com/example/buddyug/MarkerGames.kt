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

/** Tire [QUIZ_LENGTH] affirmations au hasard, avec au moins une vraie et une fausse. */
fun pickQuiz(): List<QuizItem> {
    while (true) {
        val pick = QUIZ_ITEMS.shuffled().take(QUIZ_LENGTH)
        if (pick.any { it.answer } && pick.any { !it.answer }) return pick
    }
}

// Epitech : uniquement des faits vérifiés sur epitech.eu (voir SYSTEM_PROMPT).
// Culture tech : uniquement des faits établis, sans ambiguïté.
val QUIZ_ITEMS = listOf(
    // --- Epitech : admission ---
    QuizItem("À Epitech, il faut déjà savoir coder pour entrer.", false,
        "Aucun prérequis en programmation : la motivation compte plus que le niveau de départ."),
    QuizItem("Le Programme Grande École se rejoint via Parcoursup.", true,
        "En un seul vœu, avec un concours où l'entretien de motivation compte le plus."),
    QuizItem("Le Bachelor Epitech passe aussi par Parcoursup.", false,
        "Le Bachelor est hors Parcoursup : on dépose sa candidature et on passe un entretien."),
    QuizItem("Au concours du Programme Grande École, l'épreuve qui compte le plus est l'entretien de motivation.", true,
        "Il y a aussi un test de logique, un QCM d'anglais et un questionnaire de culture tech."),
    QuizItem("Le concours du Programme Grande École comporte un QCM d'anglais.", true,
        "À côté de l'entretien de motivation, du test de logique et du questionnaire de culture tech."),
    QuizItem("Le Programme Grande École n'accepte que les bacs généraux.", false,
        "Il est ouvert aux Terminales générales, technologiques et professionnelles."),
    QuizItem("Pour entrer en Master of Science, le bac suffit.", false,
        "Il faut au minimum un Bac+3."),
    QuizItem("Le Pré-MSc s'adresse aux personnes qui ont un Bac+2.", true,
        "Une année de mise à niveau pour ensuite rejoindre un Master of Science."),
    // --- Epitech : formations ---
    QuizItem("Le Programme Grande École dure 5 ans après le bac.", true,
        "Et il mène au diplôme d'Expert en technologies de l'information, niveau Bac+5."),
    QuizItem("Le Bachelor Epitech dure 5 ans.", false,
        "Le Bachelor dure 3 ans. C'est le Programme Grande École qui dure 5 ans."),
    QuizItem("En 3e année de Bachelor, on est en alternance.", true,
        "Un an en alternance en France, pour finir le Bachelor en entreprise."),
    QuizItem("Il existe un Bachelor Cybersécurité à Epitech.", true,
        "À côté du Bachelor Intelligence Artificielle et du Bachelor Concepteur Développeur d'applications."),
    QuizItem("Les Master of Science Epitech se font en alternance.", true,
        "Un jour par semaine à l'école, le reste en entreprise."),
    QuizItem("Epitech propose un Master of Science Robotique et IoT.", true,
        "Parmi six spécialités, dont l'IA, la cybersécurité, le Big Data et le Cloud."),
    QuizItem("Au Programme Grande École, la majeure se choisit entre IA, Cybersécurité et Ingénierie logicielle.", true,
        "La majeure se fait en 5e année."),
    // --- Epitech : déroulé ---
    QuizItem("À Epitech, il y a des cours magistraux en amphi tous les jours.", false,
        "La pédagogie est par projets, sans cours magistraux."),
    QuizItem("La Piscine d'Epitech, c'est un cours de natation.", false,
        "C'est un bootcamp intensif d'immersion totale dans le code."),
    QuizItem("La Piscine du Programme Grande École dure une seule journée.", false,
        "Elle dure 5 semaines, en début de première année."),
    QuizItem("Au Programme Grande École, on part à l'international en 4e année.", true,
        "Une année complète à l'étranger, ou un stage plus un semestre à l'étranger."),
    QuizItem("Au Programme Grande École, on ne va jamais en entreprise avant la fin des études.", false,
        "Dès la 2e année, il y a un premier stage rémunéré de 4 à 6 mois."),
    QuizItem("La 5e année du Programme Grande École se passe à La Réunion.", false,
        "Elle se déroule sur le campus de Paris."),
    // --- Epitech : La Réunion ---
    QuizItem("Le campus Epitech de La Réunion est à Saint-Denis.", false,
        "Il est à Saint-André, sur la côte est de l'île."),
    QuizItem("Epitech est implantée à La Réunion depuis 2017.", true,
        "Sur le campus de Saint-André."),
    QuizItem("Epitech a 15 campus en France.", true,
        "Dont celui de Saint-André, à La Réunion."),
    QuizItem("Le Hub du campus de Saint-André a des imprimantes 3D.", true,
        "Il en a cinq, en plus des bras robotiques et des voitures autonomes."),
    QuizItem("Au Hub, il y a un espace pour les voitures autonomes.", true,
        "L'espace Robocar, pour des voitures autonomes pilotées par IA."),
    // --- Culture tech ---
    QuizItem("Un octet, c'est 8 bits.", true,
        "Et avec 8 bits, on peut écrire 256 valeurs différentes."),
    QuizItem("Le binaire n'utilise que les chiffres 0 et 1.", true,
        "C'est la base de tout ce que calcule un ordinateur."),
    QuizItem("En binaire, 10 vaut dix.", false,
        "En binaire, 10 vaut deux."),
    QuizItem("Le langage Python doit son nom au serpent.", false,
        "Il doit son nom aux Monty Python, une troupe d'humoristes britanniques."),
    QuizItem("HTML sert à structurer les pages web.", true,
        "Et CSS sert à les mettre en forme."),
    QuizItem("CSS sert à stocker les mots de passe d'un site.", false,
        "CSS sert à mettre en forme les pages web : couleurs, tailles, mise en page."),
    QuizItem("Android repose sur le noyau Linux.", true,
        "Moi aussi d'ailleurs : je tourne sous Android."),
    QuizItem("Ada Lovelace est considérée comme l'une des premières programmeuses de l'histoire.", true,
        "Au 19e siècle, elle a écrit un programme pour une machine à calculer."),
    QuizItem("Le phishing, c'est un faux message qui imite une source de confiance pour voler des informations.", true,
        "Toujours vérifier l'expéditeur avant de cliquer sur un lien."),
    QuizItem("Un mot de passe de 4 chiffres est aussi sûr qu'une longue phrase de passe.", false,
        "Plus un mot de passe est long, plus il est difficile à deviner."),
    QuizItem("Le code source d'un logiciel open source peut être consulté par tout le monde.", true,
        "C'est même le principe de l'open source."),
    QuizItem("Une IA comme moi ne se trompe jamais.", false,
        "Je prédis des réponses à partir de ce que j'ai appris, et je peux me tromper."),
    QuizItem("Le bug de l'an 2000 venait de dates stockées avec seulement 2 chiffres pour l'année.", true,
        "En passant de 99 à 00, certains programmes croyaient revenir en 1900."),
)
