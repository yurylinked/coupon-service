package pl.miskiewicz.coupons

record ApiResponse(int status, Map<String, List<String>> headers, Object body) {

    String header(String name) {
        headers.find { it.key.equalsIgnoreCase(name) }?.value?.first()
    }
}
