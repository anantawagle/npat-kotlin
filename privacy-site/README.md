# NPAT privacy page

Plain HTML and CSS, with no JavaScript, cookies, analytics, dependencies or build step.

In Vercel, import your repository, set **Root Directory** to **privacy-site**, select framework **Other**, and leave the build command unset. Deploy. Use the resulting public HTTPS address (or `/privacy-policy`) in Play Console's Privacy policy field. Do not deploy the Android repository root: it contains local signing credentials and is not the website directory.

Open `index.html` directly to preview. The canonical policy text is `app/src/main/res/raw/privacy_policy.txt`. After editing it, run `python scripts/build-privacy-site.py` from the repository root to update this HTML. Rebuild the Android app so both copies stay aligned.

The public support contact is `cutlerylabs@gmail.com`. Publish this page publicly without authentication, then add its deployed URL to the Play listing. No deployment has been performed by this project.
