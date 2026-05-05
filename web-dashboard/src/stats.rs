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
    let direction = if r > 0.0 { "positive" } else { "negative" };
    let mag = r.abs();
    let strength = if mag < 0.2 {
        "no clear"
    } else if mag < 0.5 {
        "weak"
    } else if mag < 0.8 {
        "moderate"
    } else {
        "strong"
    };
    let caveat = if n < 10 {
        format!(" (only {} pairs — interpret cautiously)", n)
    } else {
        String::new()
    };
    if mag < 0.2 {
        format!("r = {:.2} — {} trend{}", r, strength, caveat)
    } else {
        format!("r = {:.2} — {} {} correlation{}", r, strength, direction, caveat)
    }
}
