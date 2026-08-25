# sierra-app

App Android (Kotlin) que habla con Sierra. Ver `README.md` para el detalle
técnico de la app.

## Regla de ramas: una sola rama

Este repo trabaja **siempre sobre `main`**. No se crean ramas nuevas
(`claude/...`, `redesign/...`, etc.) para features, experimentos ni
sesiones sueltas — todo commit va directo a `main`.

Por qué: tener varias ramas vivas hacía que el workflow de build
(`.github/workflows/build-apk.yml`) se disparara en la rama equivocada y
que la app de auto-actualización (`GithubUpdateClient`, que lee
`apk-latest`) terminara sirviendo un APK viejo. Con una sola rama, un push
a `main` siempre dispara el build y `apk-latest` siempre refleja el commit
más reciente.

La única rama aparte de `main` es `apk-latest`, que es un artefacto
técnico: el workflow la fuerza (`push -f`) en cada build con el APK
compilado, no se edita a mano ni se mergea.

Si vas a trabajar en este repo (Claude u otra sesión): hacé `git pull
origin main`, commiteá y pusheá directo a `main`. No abras rama nueva ni
PR salvo que el usuario lo pida explícitamente.
