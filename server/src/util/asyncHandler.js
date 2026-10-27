// Express 4 doesn't forward rejected promises from async route handlers to
// the error middleware on its own; this wrapper does that.
function asyncHandler(fn) {
  return (req, res, next) => fn(req, res, next).catch(next);
}

module.exports = asyncHandler;
