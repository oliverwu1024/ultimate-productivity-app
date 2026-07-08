/// Pearson correlation coefficient. Returns None if N < 2 or zero variance.
pub fn pearson(pairs: &[(f64, f64)]) -> Option<f64> {
    if pairs.len() < 2 {
        return None;
    }
    let n = pairs.len() as f64;
    let mean_x: f64 = pairs.iter().map(|(x, _)| x).sum::<f64>() / n;
    let mean_y: f64 = pairs.iter().map(|(_, y)| y).sum::<f64>() / n;

    let mut num = 0.0;
    let mut den_x = 0.0;
    let mut den_y = 0.0;
    for (x, y) in pairs {
        let dx = x - mean_x;
        let dy = y - mean_y;
        num += dx * dy;
        den_x += dx * dx;
        den_y += dy * dy;
    }
    if den_x == 0.0 || den_y == 0.0 {
        return None;
    }
    Some(num / (den_x.sqrt() * den_y.sqrt()))
}

/// Linear regression: returns (slope, intercept) of y = slope*x + intercept.
pub fn linear_fit(pairs: &[(f64, f64)]) -> Option<(f64, f64)> {
    if pairs.len() < 2 {
        return None;
    }
    let n = pairs.len() as f64;
    let mean_x: f64 = pairs.iter().map(|(x, _)| x).sum::<f64>() / n;
    let mean_y: f64 = pairs.iter().map(|(_, y)| y).sum::<f64>() / n;

    let mut num = 0.0;
    let mut den = 0.0;
    for (x, y) in pairs {
        let dx = x - mean_x;
        num += dx * (y - mean_y);
        den += dx * dx;
    }
    if den == 0.0 {
        return None;
    }
    let slope = num / den;
    let intercept = mean_y - slope * mean_x;
    Some((slope, intercept))
}

/// Qualitative summary of a Pearson r value with sample-size caveat.
pub fn interpret_r(r: f64, n: usize) -> String {
    use crate::i18n::{t, t_args};
    let mag = r.abs();
    // Whole-phrase keys (not fragment-composed) so each translates with correct
    // adjective/word order in every language.
    let phrase_key = if mag < 0.2 {
        "cor.phrase_none"
    } else {
        let pos = r > 0.0;
        match (mag < 0.5, mag < 0.8, pos) {
            (true, _, true) => "cor.phrase_weak_pos",
            (true, _, false) => "cor.phrase_weak_neg",
            (false, true, true) => "cor.phrase_moderate_pos",
            (false, true, false) => "cor.phrase_moderate_neg",
            (false, false, true) => "cor.phrase_strong_pos",
            (false, false, false) => "cor.phrase_strong_neg",
        }
    };
    let phrase = t(phrase_key);
    let rs = format!("{:.2}", r);
    let caveat = if n < 10 {
        let ns = n.to_string();
        t_args("cor.caveat", &[("count", ns.as_str())])
    } else {
        String::new()
    };
    t_args(
        "cor.interp",
        &[("r", rs.as_str()), ("phrase", phrase.as_str()), ("caveat", caveat.as_str())],
    )
}
