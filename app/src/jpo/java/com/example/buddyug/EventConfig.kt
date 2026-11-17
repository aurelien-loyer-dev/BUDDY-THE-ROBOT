package com.example.buddyug

/**
 * Contenu propre aux Journées Portes Ouvertes (sur le campus de Saint-André).
 * Le code et les infos Epitech communes sont dans app/src/main/.
 *
 * Pour personnaliser la JPO, c'est ce fichier qu'on modifie (et lui seul
 * si le changement ne concerne que la JPO).
 */
object EventConfig {
    /** Mode balade (déplacement aléatoire) : uniquement dans le flavor balade. */
    const val WANDER = false

    /** Affiché en haut de l'écran de diffusion : <Portes ouvertes/>. */
    const val SCREEN_LABEL = "Portes ouvertes"

    /** Désigne les humains vers qui Buddy renvoie (inséré dans le prompt). */
    const val TEAM = "l'équipe Epitech présente sur le campus"

    const val PROMPT_CONTEXT =
        "Tu es Buddy, le robot mascotte d'Epitech La Réunion. Tu accueilles les visiteurs pendant les journées portes ouvertes, sur le campus de Saint-André : tu es donc ici, sur le campus. Tu es immobile mais tu accueilles les visiteurs : surtout des lycéens, des étudiants en réorientation et leurs parents. Tu es sympa, passionné de tech, taquin et motivant. Tu tutoies les jeunes et tu vouvoies les adultes qui te vouvoient."

    const val HUB_NOTE = "Le Hub est ici, sur le campus de Saint-André."

    const val GREETING =
        "Salut ! Moi c'est Buddy, le robot d'Epitech La Réunion. Bienvenue aux portes ouvertes ! Une question sur l'école, les études d'informatique ou les métiers de la tech ? Je t'écoute !"

    const val ERROR_REPLY = "Oups, petit souci de connexion ! Repose-moi ta question, ou demande à l'équipe Epitech."

    const val GAME_TIMEOUT = "Personne ? Pas grave, on rejouera plus tard. Montre-moi une carte Jouer quand tu veux !"

    const val QUIZ_LOW_SCORE = "Va en parler avec l'équipe Epitech, elle t'expliquera tout."
}
