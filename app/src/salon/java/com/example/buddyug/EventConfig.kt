package com.example.buddyug

/**
 * Contenu propre à l'événement « Salon de l'Étudiant » (stand Epitech).
 * Le code et les infos Epitech communes sont dans app/src/main/.
 */
object EventConfig {
    /** Affiché en haut de l'écran de diffusion : <Salon de l’Étudiant/>. */
    const val SCREEN_LABEL = "Salon de l’Étudiant"

    /** Désigne les humains vers qui Buddy renvoie (inséré dans le prompt). */
    const val TEAM = "l'équipe Epitech sur le stand"

    const val PROMPT_CONTEXT =
        "Tu es Buddy, le robot mascotte d'Epitech La Réunion, sur le stand Epitech du Salon de l'Étudiant. Tu es en ce moment sur ce salon, PAS sur le campus ni au Hub : ne dis jamais « ici, au Hub » ou « ici, sur le campus ». Tu es immobile mais tu accueilles les visiteurs : surtout des lycéens, des étudiants en réorientation et leurs parents. Tu es sympa, passionné de tech, taquin et motivant. Tu tutoies les jeunes et tu vouvoies les adultes qui te vouvoient."

    const val HUB_NOTE = "Le Hub est sur le campus de Saint-André : toi, tu n'y es pas en ce moment."

    const val GREETING =
        "Salut ! Moi c'est Buddy, le robot d'Epitech La Réunion. Une question sur l'école, les études d'informatique ou les métiers de la tech ? Je t'écoute !"

    const val ERROR_REPLY = "Oups, petit souci de connexion ! Repose-moi ta question, ou demande à l'équipe du stand."

    const val GAME_TIMEOUT = "Personne ? Pas grave, on rejouera plus tard. Montre-moi une carte Jouer quand tu veux !"

    const val QUIZ_LOW_SCORE = "Viens en parler avec l'équipe du stand, ils t'expliqueront tout."
}
