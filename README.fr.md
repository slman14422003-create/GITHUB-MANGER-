# GitHub Manager

Langues : [English](README.md) | [العربية](README.ar.md) | [Français](README.fr.md) | [Español](README.es.md) | [Türkçe](README.tr.md) | [Русский](README.ru.md)

Utilisation sure et protection contre les abus : [SAFE_USE.md](SAFE_USE.md)

GitHub Manager est une application Android native (Java) pour gerer depuis un telephone les depots GitHub, les fichiers, les workflows Actions, les issues et les releases.

## Fonctionnalites

Depots et fichiers
- Lister, rechercher et filtrer vos depots, et nettoyer les anciens.
- Envoyer des dossiers entiers depuis le telephone en gardant l'arborescence. L'application ignore les fichiers identiques, respecte .gitignore, affiche un apercu (nouveau, modifie, identique) avant l'envoi et reprend apres un echec.
- Telecharger des fichiers, des dossiers ou des depots entiers avec pause, reprise et annulation. Les transferts continuent en arriere-plan avec une notification.
- Gestionnaire de fichiers de l'appareil (liste et grille), selection multiple par glissement, recherche, et corbeille interne videe automatiquement apres 30 jours.
- Editeur de code avec coloration syntaxique, numeros de ligne, annuler et refaire, recherche dans le code, rendu Markdown et diff par ligne.

Branches et commits
- Creer, fusionner et supprimer des branches, et definir la branche par defaut.
- Parcourir l'historique des commits et voir le diff de chacun.

GitHub Actions
- Lister les workflows et suivre les executions en direct, avec chaque job, chaque etape et un chronometre.
- Visionneuse de journaux avec regroupement par etape, numeros de ligne et filtre erreurs seulement.
- Lancer un workflow manuellement avec des entrees, et nettoyer les anciennes executions.
- Telecharger les artifacts, avec extraction automatique de l'APK.
- Compiler et publier une release depuis un seul ecran.
- SEMO AI : une verification hors ligne avant le lancement d'un workflow. Elle detecte les parentheses ou accolades non equilibrees et les erreurs de format sures, et ne corrige que ce qui est certain. Aucun appel reseau, aucun modele.

Issues, releases et collaboration
- Issues : lister, filtrer, creer, commenter et modifier.
- Releases : consulter, publier, supprimer et telecharger leurs fichiers.
- Collaborateurs : inviter des personnes et changer leur niveau d'acces.
- Statistiques du depot : langages, trafic et contributeurs.
- Parametres de securite : protection des branches, analyse des secrets, protection au push, mises a jour de securite Dependabot et permissions Actions par defaut.
- Secrets Actions chiffres et configuration de la cle de signature.

Securite et confidentialite
- Le jeton d'acces est stocke avec l'Android Keystore.
- Verrouillage facultatif par empreinte, schema ou code du telephone.
- Les captures d'ecran sont bloquees par defaut dans l'application ; on peut les autoriser dans Reglages, Securite.
- HTTPS uniquement, avec les certificats du systeme seulement. Sauvegardes desactivees.
- L'APK peut etre scelle a la compilation et l'application verifie le sceau au demarrage.
- Les mises a jour sont verifiees par SHA-256 et par le certificat de signature.

Autres
- Miroir facultatif via un Cloudflare Worker pour les reseaux ou GitHub est lent ou bloque.
- Interface en arabe et en anglais, themes clair et sombre, plusieurs comptes.

## Activation

1. Installer l'APK
   - Telechargez l'APK depuis la page Releases de ce depot et installez-le. Autorisez "Installer des applications inconnues" pour l'application qui a ouvert le fichier.
   - Android 7.0 (API 24) ou plus recent est requis.

2. Se connecter (au choix)
   - Device flow : choisissez l'option Google ou e-mail, GitHub affiche un code, saisissez-le sur la page officielle de GitHub. Votre mot de passe ne passe jamais par l'application. Il faut une OAuth App GitHub avec Device Flow active ; son Client ID est public par conception. Placez-le dans la variable de depot GH_OAUTH_CLIENT_ID (Settings, Secrets and variables, Actions, Variables) avant la compilation, ou saisissez-le dans l'application avec l'option Client ID.
   - Jeton d'acces personnel : creez un jeton sur GitHub et collez-le dans l'application. Ne donnez que les droits necessaires. Le device flow demande : repo, workflow, delete_repo, read:org, user, admin:public_key.

3. Protection facultative
   - Parametres, Verrouillage de l'application : activez-le et choisissez le delai. Un verrouillage d'ecran doit deja etre configure sur le telephone.

## Compiler depuis les sources avec GitHub Actions

1. Creez une cle de signature permanente. Lancez le workflow "Generate signing key" uniquement dans une copie PRIVEE du depot ; il refuse de s'executer sur un depot public. Telechargez l'artifact, rangez les fichiers hors ligne en lieu sur, puis supprimez l'artifact. Vous pouvez aussi lancer tools/make-keystore.sh sur votre ordinateur.
2. Ajoutez les secrets du depot : KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD.
3. Lancez le workflow "Build APK". La release apparait sur la page Releases.
4. Ne publiez jamais le keystore, les mots de passe ou les jetons dans le depot.

## Deployer le Cloudflare Worker (facultatif)

Le Worker ne transmet les requetes qu'aux hotes GitHub et refuse toute requete qui ne porte pas votre cle secrete.

1. Installez Node.js et ouvrez un terminal dans le dossier worker.
2. Connexion : npx wrangler login
3. Deploiement : npx wrangler deploy
4. Creez une longue cle aleatoire : openssl rand -hex 32
5. Enregistrez-la comme secret : npx wrangler secret put MIRROR_KEY puis collez la cle. Sans ce secret, le Worker repond 503 a tout.
6. Dans l'application, ouvrez Parametres, Miroir (Mirror). Saisissez l'adresse du Worker et la cle, puis appuyez sur Tester.
7. Recommande : dans Cloudflare, desactivez l'adresse workers.dev, utilisez votre propre domaine et ajoutez une regle de limitation de debit. Activez l'authentification a deux facteurs sur votre compte Cloudflare.
8. Si la cle est exposee, remplacez-la immediatement avec la meme commande.

## Respecter les regles de GitHub

Lisez SAFE_USE.md pour la politique complete de protection et d'abus.
