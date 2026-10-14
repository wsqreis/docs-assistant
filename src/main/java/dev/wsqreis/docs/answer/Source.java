package dev.wsqreis.docs.answer;

/** A documentation section given to the model, numbered as the model cites it: [n]. */
public record Source(int n, String title, String breadcrumb, String url) {
}
